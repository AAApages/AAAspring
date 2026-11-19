package com.open.spring.mvc.assignments;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.aiconnections.AiHouseModelClient;
import com.open.spring.mvc.aiconnections.AiHouseSettingsRepository;

import jakarta.annotation.PreDestroy;

/**
 * Night grading: while the night window is open, grades ungraded submissions one at a time with
 * the self-hosted model only, then generates missing rubrics. No paid model is called. Without a
 * house model (or with the server down) it grades nothing, reports what is waiting, and leaves the
 * work queued for the daytime cloud job.
 *
 * Work order: queued submissions (needs_ai_regrade), then never AI-graded submissions, then open
 * assignments without a ready rubric. Each item is tried at most once per night. Grades go through
 * {@link SubmissionAiGrader} and {@link SubmissionGradingService}, so manual grades, revisions and
 * closed assignments are respected. Shares the queue lock with {@link AssignmentAiRegradeService}.
 */
@Service
public class AssignmentNightGradingService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentNightGradingService.class);

    enum Kind { QUEUED, UNGRADED, RUBRIC }

    record WorkItem(Kind kind, long id, long assignmentId) {
    }

    /** How much work is waiting for the house model. */
    public record Waiting(int queued, int ungraded, int rubrics) {
        public int total() {
            return queued + ungraded + rubrics;
        }
    }

    /** One night run; {@code endReason} is null while it is still going. */
    public record RunSummary(Instant startedAt, Instant endedAt, boolean manual, int graded, int failed,
            int notGradeable, int rubrics, String endReason) {
    }

    /** What the queue page shows about night grading. */
    public record Status(boolean enabled, String windowStart, String windowEnd, String zone, boolean windowOpen,
            boolean running, String preset, boolean houseConfigured, boolean houseAvailable, Instant houseDownUntil,
            List<String> models, Waiting waiting, RunSummary lastRun, Instant lastCheckAt) {
    }

    /** Result of a manual start: started, busy, no_house_model or house_unavailable. */
    public record StartResult(String status, Waiting waiting) {
    }

    private final AssignmentAiRegradeService queue;
    private final AssignmentJpaRepository assignmentRepo;
    private final SubmissionGradingService gradingService;
    private final AiHouseSettingsRepository settings;
    private final AiHouseModelClient house;
    private final JdbcTemplate jdbcTemplate;
    /** Items tried tonight ("S:id" submissions, "A:id" assignments whose rubric failed, "R:id" rubrics). */
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private Executor executor = task -> {
        Thread thread = new Thread(task, "night-grading");
        thread.setDaemon(true);
        thread.start();
    };
    private volatile Thread worker;
    private volatile RunSummary lastRun;
    private volatile Instant lastCheckAt;

    public AssignmentNightGradingService(AssignmentAiRegradeService queue, AssignmentJpaRepository assignmentRepo,
            SubmissionGradingService gradingService, AiHouseSettingsRepository settings, AiHouseModelClient house,
            JdbcTemplate jdbcTemplate) {
        this.queue = queue;
        this.assignmentRepo = assignmentRepo;
        this.gradingService = gradingService;
        this.settings = settings;
        this.house = house;
        this.jdbcTemplate = jdbcTemplate;
    }

    void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /** Every few minutes: start a run when the window is open, the house model is up and work is waiting. */
    @Scheduled(initialDelayString = "${assignments.night-grading.initial-delay-ms:90000}",
            fixedDelayString = "${assignments.night-grading.tick-ms:300000}")
    public void tick() {
        try {
            if (!settings.isNightWindowOpen()) {
                // A new night retries everything. Not while a manual daytime pass runs, or it would
                // retry a failing item forever and keep the cloud queue waiting.
                if (worker == null) {
                    attempted.clear();
                }
                return;
            }
            lastCheckAt = Instant.now();
            if (!house.isAvailable()) {
                logger.info("Night grading: no house model available; waiting={} (left queued)", waiting().total());
                return;
            }
            if (nextItem() != null && queue.tryAcquireRun()) {
                executor.execute(() -> runLoop(false));
            }
        } catch (RuntimeException e) {
            logger.warn("Night grading tick failed: {}", e.getMessage());
        }
    }

    /** Starts a house-only run now, ignoring the window, until the work runs out. */
    public StartResult startNow() {
        if (!house.isConfigured()) {
            return new StartResult("no_house_model", waiting());
        }
        if (!house.isAvailable()) {
            return new StartResult("house_unavailable", waiting());
        }
        if (!queue.tryAcquireRun()) {
            return new StartResult("busy", null);
        }
        attempted.clear();
        Waiting waiting = waiting();
        executor.execute(() -> runLoop(true));
        return new StartResult("started", waiting);
    }

    void runLoop(boolean manual) {
        worker = Thread.currentThread();
        Instant started = Instant.now();
        int graded = 0;
        int failed = 0;
        int notGradeable = 0;
        int rubrics = 0;
        String endReason = "no work left";
        logger.info("Night grading run started manual={} models={}", manual, settings.current().models());
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    endReason = "server stopping";
                    break;
                }
                if (!manual && !settings.isNightWindowOpen()) {
                    endReason = "window closed";
                    break;
                }
                if (!house.isAvailable()) {
                    endReason = "house model unavailable";
                    break;
                }
                WorkItem item = nextItem();
                if (item == null) {
                    break;
                }
                lastRun = new RunSummary(started, null, manual, graded, failed, notGradeable, rubrics, null);
                switch (process(item)) {
                    case GRADED -> graded++;
                    case RUBRIC -> rubrics++;
                    case NOT_GRADEABLE -> notGradeable++;
                    case FAILED -> failed++;
                    case SKIPPED -> { }
                }
            }
        } catch (RuntimeException e) {
            endReason = "error";
            logger.warn("Night grading run failed: {}", e.getMessage());
        } finally {
            lastRun = new RunSummary(started, Instant.now(), manual, graded, failed, notGradeable, rubrics, endReason);
            worker = null;
            queue.releaseRun();
            logger.info("Night grading run ended reason={} graded={} failed={} notGradeable={} rubrics={}",
                    endReason, graded, failed, notGradeable, rubrics);
        }
    }

    enum Result { GRADED, RUBRIC, NOT_GRADEABLE, FAILED, SKIPPED }

    Result process(WorkItem item) {
        Assignment assignment = assignmentRepo.findById(item.assignmentId()).orElse(null);
        if (item.kind() == Kind.RUBRIC) {
            attempted.add("R:" + item.id());
            if (assignment == null || assignment.isClosed()) {
                return Result.SKIPPED;
            }
            boolean ready = queue.ensureAiRubric(assignment, true);
            if (!ready && !house.isAvailable()) {
                attempted.remove("R:" + item.id()); // The server went down; not this rubric's fault.
            }
            return ready ? Result.RUBRIC : Result.FAILED;
        }
        String key = "S:" + item.id();
        attempted.add(key);
        if (assignment == null || assignment.isClosed()) {
            return Result.SKIPPED;
        }
        // Grade against the assignment's own rubric; if it can't be made tonight, skip its submissions
        // rather than spend a slow grade on the default rubric.
        if (!queue.ensureAiRubric(assignment, true)) {
            if (house.isAvailable()) {
                attempted.add("A:" + assignment.getId());
            } else {
                attempted.remove(key);
            }
            return Result.FAILED;
        }
        GradingOutcome outcome = queue.gradeOnce(item.id(), assignment, item.kind() == Kind.QUEUED, true);
        if (outcome == GradingOutcome.AI_GRADE_APPLIED || outcome == GradingOutcome.MANUAL_OVERRIDE_KEPT) {
            return Result.GRADED;
        }
        if (outcome == GradingOutcome.AI_NOT_GRADEABLE) {
            return Result.NOT_GRADEABLE;
        }
        if (outcome == GradingOutcome.AI_FAILED || outcome == null) {
            if (!house.isAvailable()) {
                attempted.remove(key); // The server went down mid-grade; retry when it is back.
            }
            if (item.kind() == Kind.UNGRADED) {
                // Show it in the queue so the daytime job grades it if the house model can't.
                gradingService.currentState(item.id())
                        .ifPresent(state -> gradingService.flagForAiRegrade(item.id(), state.revision()));
            }
            return Result.FAILED;
        }
        return Result.SKIPPED; // Closed, stale revision or gone meanwhile.
    }

    /** The next item not yet tried tonight, or null. */
    WorkItem nextItem() {
        for (Map<String, Object> row : queuedRows()) {
            WorkItem item = submission(Kind.QUEUED, row);
            if (item != null) {
                return item;
            }
        }
        for (Map<String, Object> row : ungradedRows()) {
            WorkItem item = submission(Kind.UNGRADED, row);
            if (item != null) {
                return item;
            }
        }
        for (Long assignmentId : missingRubricIds()) {
            if (!attempted.contains("R:" + assignmentId) && !attempted.contains("A:" + assignmentId)) {
                return new WorkItem(Kind.RUBRIC, assignmentId, assignmentId);
            }
        }
        return null;
    }

    private WorkItem submission(Kind kind, Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        long assignmentId = ((Number) row.get("assignment_id")).longValue();
        if (attempted.contains("S:" + id) || attempted.contains("A:" + assignmentId) || queue.isGradingNow(id)) {
            return null;
        }
        return new WorkItem(kind, id, assignmentId);
    }

    private List<Map<String, Object>> queuedRows() {
        return jdbcTemplate.queryForList("SELECT s.id, s.assignment_id FROM assignment_submission s "
                + "JOIN assignment a ON a.id = s.assignment_id "
                + "WHERE s.needs_ai_regrade = 1 AND a.closed_at IS NULL AND "
                + AssignmentSubmissionHistoryService.currentRowSql("s") + " ORDER BY s.id");
    }

    private List<Map<String, Object>> ungradedRows() {
        return jdbcTemplate.queryForList("SELECT s.id, s.assignment_id FROM assignment_submission s "
                + "JOIN assignment a ON a.id = s.assignment_id "
                + "WHERE s.ai_grade IS NULL AND s.grade IS NULL "
                + "AND (s.manual_override IS NULL OR s.manual_override = 0) "
                + "AND (s.needs_ai_regrade IS NULL OR s.needs_ai_regrade = 0) "
                + "AND a.closed_at IS NULL AND " + AssignmentSubmissionHistoryService.currentRowSql("s")
                + " ORDER BY s.id");
    }

    private List<Long> missingRubricIds() {
        return jdbcTemplate.queryForList("SELECT id, ai_rubric, points FROM assignment WHERE closed_at IS NULL "
                + "ORDER BY id DESC").stream()
                .filter(row -> !AssignmentRubricService.isAiRubricReady((String) row.get("ai_rubric"),
                        row.get("points") == null ? null : ((Number) row.get("points")).doubleValue()))
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();
    }

    /** Counts of the work waiting for the house model (whether or not it was tried tonight). */
    public Waiting waiting() {
        try {
            return new Waiting(queuedRows().size(), ungradedRows().size(), missingRubricIds().size());
        } catch (RuntimeException e) {
            logger.warn("Night grading: could not count waiting work: {}", e.getMessage());
            return new Waiting(0, 0, 0);
        }
    }

    public Status status() {
        AiHouseSettingsRepository.HouseSettings current = settings.current();
        return new Status(current.enabled(), current.windowStart(), current.windowEnd(), current.zone(),
                settings.isNightWindowOpen(), worker != null, current.preset().name(), house.isConfigured(),
                house.isAvailable(), house.downUntil(), current.modelConfigured() ? current.models() : List.of(),
                waiting(), lastRun, lastCheckAt);
    }

    @PreDestroy
    void shutdown() {
        Thread running = worker;
        if (running != null) {
            running.interrupt();
        }
    }
}
