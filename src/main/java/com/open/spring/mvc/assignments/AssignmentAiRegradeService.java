package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.open.spring.mvc.aiconnections.AiHouseModelClient;
import com.open.spring.mvc.aiconnections.AiHouseSettingsRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Re-grades submissions that were AI-graded with the default rubric because their
 * assignment's AI rubric wasn't ready yet. Each run first generates any missing rubric
 * (from the assignment's name/description) and then re-grades the flagged submissions
 * against it. Runs on a timer, and after auto-create queues a rubric via {@link #requestRubric}.
 *
 * Also owns the needs_ai_regrade column migration (the schema uses ddl-auto=none), and
 * uses plain SQL for its two bulk queries so the JPA repositories stay untouched.
 */
@Service
@EnableScheduling
public class AssignmentAiRegradeService {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentAiRegradeService.class);

    private final AssignmentSubmissionJPA submissionRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final SubmissionAiGrader aiGrader;
    private final SubmissionGradingService gradingService;
    private final AssignmentRubricService rubricService;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Submissions being graded right now via {@link #gradeNow}; the timed run skips them. */
    private final Set<Long> gradingNow = ConcurrentHashMap.newKeySet();
    private volatile FixGradesStatus fixStatus = new FixGradesStatus(false, 0, 0, 0, 0);
    // Rolling average of how long one background grade takes, for the queue ETA.
    private volatile double avgGradeSeconds = 15.0;
    private volatile long nextRunAtMillis;
    @Value("${assignments.ai-regrade.initial-delay-ms:60000}")
    private long initialDelayMs = 60000;
    @Value("${assignments.ai-regrade.interval-ms:300000}")
    private long intervalMs = 300000;
    // Gemini requests per minute the key allows; the ETA never assumes grading faster than this.
    @Value("${assignments.ai-grade.rpm:15}")
    private int gradesPerMinute = 15;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "assignment-ai-regrade");
        thread.setDaemon(true);
        return thread;
    });

    private final AiHouseSettingsRepository nightSettings;
    private final AiHouseModelClient houseModel;
    // Rolling average of one overnight (house model) grade, for night-time ETAs.
    private volatile double avgNightGradeSeconds = 300.0;

    public AssignmentAiRegradeService(
            AssignmentSubmissionJPA submissionRepo,
            AssignmentJpaRepository assignmentRepo,
            SubmissionAiGrader aiGrader,
            SubmissionGradingService gradingService,
            AssignmentRubricService rubricService,
            JdbcTemplate jdbcTemplate) {
        this(submissionRepo, assignmentRepo, aiGrader, gradingService, rubricService, jdbcTemplate, null, null);
    }

    @Autowired
    public AssignmentAiRegradeService(
            AssignmentSubmissionJPA submissionRepo,
            AssignmentJpaRepository assignmentRepo,
            SubmissionAiGrader aiGrader,
            SubmissionGradingService gradingService,
            AssignmentRubricService rubricService,
            JdbcTemplate jdbcTemplate,
            AiHouseSettingsRepository nightSettings,
            AiHouseModelClient houseModel) {
        this.submissionRepo = submissionRepo;
        this.assignmentRepo = assignmentRepo;
        this.aiGrader = aiGrader;
        this.gradingService = gradingService;
        this.rubricService = rubricService;
        this.jdbcTemplate = jdbcTemplate;
        this.nightSettings = nightSettings;
        this.houseModel = houseModel;
    }

    /** During the night window automated grading uses only the house model (night grading run). */
    public boolean nightWindowOpen() {
        return nightSettings != null && nightSettings.isNightWindowOpen();
    }

    /**
     * Adds the needs_ai_regrade, hack_weights and graded_at columns; runs before other startup runners
     * that load submissions or assignments.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner migrateSubmissionRegradeFlag() {
        return args -> {
            addColumn("assignment_submission", "needs_ai_regrade BOOLEAN");
            addColumn("assignment", "hack_weights TEXT");
            addColumn("assignment_submission", "graded_at DATETIME");
        };
    }

    private void addColumn(String table, String columnDefinition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition);
            logger.info("Added {} column to {}", columnDefinition, table);
        } catch (DataAccessException e) {
            logger.debug("{} column not added to {} (already exists?): {}", columnDefinition, table, e.getMessage());
        }
    }

    /**
     * Generates the assignment's AI rubric in the background (if it still isn't ready), then
     * re-grades anything graded with the default rubric meanwhile. The Gemini call takes ~10s,
     * so doing it inside the auto-create request slowed every page view and held a SQLite
     * write lock long enough for a concurrent auto-create to fail with SQLITE_BUSY_SNAPSHOT.
     */
    public void requestRubric(Long assignmentId, String name, String description, String pageContent) {
        if (nightWindowOpen()) {
            logger.info("Rubric for assignment {} deferred to the night grading run", assignmentId);
            return;
        }
        afterCommit(() -> {
            try {
                Assignment assignment = assignmentRepo.findById(assignmentId).orElse(null);
                if (assignment == null
                        || AssignmentRubricService.isAiRubricReady(assignment.getAiRubric(), assignment.getPoints())) {
                    return; // Gone, or an earlier queued request already generated it.
                }
                String generated = rubricService.generateRubric(name, description, pageContent, assignment.getPoints());
                if (generated != null) {
                    jdbcTemplate.update("UPDATE assignment SET ai_rubric = ? WHERE id = ?", generated, assignmentId);
                    logger.info("Generated AI rubric for assignment {}", assignmentId);
                }
            } catch (Exception e) {
                logger.warn("Background rubric generation failed for assignment {}: {}", assignmentId, e.getMessage());
            }
            runOnce();
        });
    }

    /** Runs the task on the background thread, after the caller's transaction commits if there is one. */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    @PostConstruct
    void initNextRun() {
        nextRunAtMillis = System.currentTimeMillis() + initialDelayMs;
    }

    @Scheduled(
            initialDelayString = "${assignments.ai-regrade.initial-delay-ms:60000}",
            fixedDelayString = "${assignments.ai-regrade.interval-ms:300000}")
    public void scheduledRun() {
        runOnce();
    }

    void runOnce() {
        if (nightWindowOpen() || !running.compareAndSet(false, true)) {
            return; // At night the queue belongs to the night grading run (house model only).
        }
        try {
            // Only current submissions: an older row a resubmit superseded (from before resubmits
            // replaced the row) is history, not work waiting for a grade.
            List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                    "SELECT s.id, s.assignment_id FROM assignment_submission s WHERE s.needs_ai_regrade = 1 AND "
                            + AssignmentSubmissionHistoryService.currentRowSql("s") + " ORDER BY s.id");
            if (pending.isEmpty()) {
                return;
            }
            Map<Long, List<Long>> byAssignment = groupByAssignment(pending);
            logger.info("AI re-grade: {} flagged submission(s) across {} assignment(s)",
                    pending.size(), byAssignment.size());
            for (Map.Entry<Long, List<Long>> entry : byAssignment.entrySet()) {
                Assignment assignment = assignmentRepo.findById(entry.getKey()).orElse(null);
                if (assignment != null && !assignment.isClosed() && ensureAiRubric(assignment)) {
                    entry.getValue().stream().filter(submissionId -> !gradingNow.contains(submissionId))
                            .forEach(submissionId -> regrade(submissionId, assignment, true));
                }
            }
        } catch (Exception e) {
            logger.warn("AI re-grade run failed: {}", e.getMessage());
        } finally {
            nextRunAtMillis = System.currentTimeMillis() + intervalMs;
            running.set(false);
        }
    }

    /**
     * Starts a full queue run now instead of waiting for the timer (teacher "run queue now").
     * Runs on its own thread so it doesn't hold up rubric generation on the executor.
     * Returns false if a run is already in progress.
     */
    public boolean runQueueNow() {
        if (running.get() || nightWindowOpen()) {
            return false;
        }
        Thread thread = new Thread(this::runOnce, "ai-regrade-now");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public record GradeNowResult(String status, String message) {
    }

    /**
     * Skips the queue: grades one queued submission right away (generating the assignment's
     * rubric first if needed), blocking until done. Only queued submissions, so a teacher's
     * final grade can't be replaced from here.
     */
    public GradeNowResult gradeNow(Long submissionId) {
        if (!gradingNow.add(submissionId)) {
            return new GradeNowResult("busy", "This submission is already being graded.");
        }
        try {
            AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
            if (submission == null || !Boolean.TRUE.equals(submission.getNeedsAiRegrade())) {
                return new GradeNowResult("not_queued", "This submission is no longer in the AI queue.");
            }
            Long assignmentId = jdbcTemplate.queryForObject(
                    "SELECT assignment_id FROM assignment_submission WHERE id = ?", Long.class, submissionId);
            Assignment assignment = assignmentId == null ? null : assignmentRepo.findById(assignmentId).orElse(null);
            if (assignment == null) {
                return new GradeNowResult("failed", "The submission's assignment no longer exists.");
            }
            if (!ensureAiRubric(assignment)) {
                return new GradeNowResult("failed", "Could not generate a rubric for this assignment; try again.");
            }
            return regrade(submissionId, assignment, true)
                    ? new GradeNowResult("graded", "Graded.")
                    : new GradeNowResult("failed", "The AI grader could not grade it right now; see the server log.");
        } finally {
            gradingNow.remove(submissionId);
        }
    }

    /**
     * One submission waiting in the AI grading queue; position is 1-based. The submitter is a
     * person (name + uid) or a group (group name, uid null, members as "Name (uid)").
     */
    public record QueueEntry(long submissionId, long assignmentId, String assignmentName, Long submitterId,
            String submitterName, String submitterUid, List<String> members,
            int position, long etaSeconds, boolean gradingNow) {
    }

    public record QueueSnapshot(boolean running, long nextRunInSeconds, double secondsPerGrade,
            List<QueueEntry> entries) {
    }

    /**
     * The flagged submissions in the order {@link #runOnce} grades them (by assignment, in order of
     * each assignment's first flagged submission), each with an ETA from the next run time and the
     * average grade time, capped by the key's rate limit.
     */
    public QueueSnapshot queueSnapshot() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id, s.assignment_id, s.submitter_id, a.name, p.name AS person_name, p.uid, "
                        + "g.name AS group_name FROM assignment_submission s "
                        + "LEFT JOIN assignment a ON a.id = s.assignment_id "
                        + "LEFT JOIN person p ON p.id = s.submitter_id "
                        + "LEFT JOIN `groups` g ON g.id = s.submitter_id "
                        + "WHERE s.needs_ai_regrade = 1 AND " + AssignmentSubmissionHistoryService.currentRowSql("s")
                        + " ORDER BY s.id");
        Map<Long, List<String>> groupMembers = new HashMap<>();
        jdbcTemplate.queryForList(
                "SELECT DISTINCT gm.group_id, p.name, p.uid FROM group_members gm "
                        + "JOIN person p ON p.id = gm.person_id "
                        + "JOIN assignment_submission s ON s.submitter_id = gm.group_id "
                        + "WHERE s.needs_ai_regrade = 1 AND " + AssignmentSubmissionHistoryService.currentRowSql("s")
                        + " ORDER BY p.name")
                .forEach(row -> groupMembers
                        .computeIfAbsent(((Number) row.get("group_id")).longValue(), id -> new ArrayList<>())
                        .add(row.get("name") + " (" + row.get("uid") + ")"));
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(((Number) row.get("id")).longValue(), row));
        boolean isRunning = running.get();
        long untilNextRun = isRunning ? 0 : Math.max(0, (nextRunAtMillis - System.currentTimeMillis()) / 1000);
        double perGrade = secondsPerGrade();
        if (nightWindowOpen()) {
            // At night only the house model grades; without one, the cloud queue resumes when the window ends.
            boolean house = houseModel != null && houseModel.isAvailable();
            isRunning = house && isRunning;
            untilNextRun = house ? (isRunning ? 0 : untilNextRun)
                    : Math.max(0, (nightSettings.nightWindowEnd().toEpochMilli() - System.currentTimeMillis()) / 1000);
            perGrade = house ? avgNightGradeSeconds : perGrade;
        }
        List<QueueEntry> entries = new ArrayList<>();
        for (List<Long> ids : groupByAssignment(rows).values()) {
            for (Long id : ids) {
                Map<String, Object> row = byId.get(id);
                int position = entries.size() + 1;
                Number submitter = (Number) row.get("submitter_id");
                Long submitterId = submitter == null ? null : submitter.longValue();
                boolean isGroup = row.get("person_name") == null && row.get("uid") == null && row.get("group_name") != null;
                entries.add(new QueueEntry(id, ((Number) row.get("assignment_id")).longValue(),
                        (String) row.get("name"), submitterId,
                        (String) (isGroup ? row.get("group_name") : row.get("person_name")),
                        (String) row.get("uid"),
                        isGroup ? groupMembers.getOrDefault(submitterId, List.of()) : List.of(),
                        position, untilNextRun + Math.round(position * perGrade), gradingNow.contains(id)));
            }
        }
        return new QueueSnapshot(isRunning, untilNextRun, perGrade, entries);
    }

    double secondsPerGrade() {
        return Math.max(avgGradeSeconds, 60.0 / Math.max(1, gradesPerMinute));
    }

    void recordGradeSeconds(double seconds) {
        avgGradeSeconds = avgGradeSeconds * 0.8 + seconds * 0.2;
    }

    /** Progress of the latest "fix out-of-range grades" run, polled by the submissions page. */
    public record FixGradesStatus(boolean running, int total, int done, int fixed, int failed) {
    }

    public FixGradesStatus getFixStatus() {
        return fixStatus;
    }

    /**
     * Re-grades every AI-graded submission whose grade is above its assignment's points (e.g. 4/1
     * from the old 2-4 scale) against the assignment's rubric, skipping manual overrides and
     * closed assignments. Each submission is attempted exactly once per run; a failure is counted
     * and left alone (no flag, no retry), so it can't loop.
     */
    public synchronized FixGradesStatus startFixOutOfRangeGrades() {
        if (fixStatus.running()) {
            return fixStatus;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id, s.assignment_id FROM assignment_submission s JOIN assignment a ON a.id = s.assignment_id "
                        + "WHERE s.grade IS NOT NULL AND (s.manual_override IS NULL OR s.manual_override = 0) "
                        + "AND a.closed_at IS NULL "
                        + "AND s.grade > (CASE WHEN a.points IS NULL OR a.points <= 0 THEN 1 ELSE a.points END) + 0.000001 "
                        + "ORDER BY s.assignment_id, s.id");
        Map<Long, List<Long>> byAssignment = groupByAssignment(rows);
        fixStatus = new FixGradesStatus(!rows.isEmpty(), rows.size(), 0, 0, 0);
        if (!rows.isEmpty()) {
            executor.execute(() -> fixOutOfRangeGrades(byAssignment, rows.size()));
        }
        return fixStatus;
    }

    private void fixOutOfRangeGrades(Map<Long, List<Long>> byAssignment, int total) {
        int done = 0;
        int fixed = 0;
        try {
            for (Map.Entry<Long, List<Long>> entry : byAssignment.entrySet()) {
                Assignment assignment = assignmentRepo.findById(entry.getKey()).orElse(null);
                if (assignment != null) {
                    // Best effort: without an AI rubric the default one (out of 1, scaled to points) is used.
                    ensureAiRubric(assignment);
                }
                for (Long submissionId : entry.getValue()) {
                    if (assignment != null && regrade(submissionId, assignment, false)) {
                        fixed++;
                    }
                    done++;
                    fixStatus = new FixGradesStatus(true, total, done, fixed, done - fixed);
                }
            }
        } finally {
            fixStatus = new FixGradesStatus(false, total, total, fixed, total - fixed);
            logger.warn("Fix out-of-range grades: {} of {} re-graded", fixed, total);
        }
    }

    private static Map<Long, List<Long>> groupByAssignment(List<Map<String, Object>> rows) {
        Map<Long, List<Long>> byAssignment = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byAssignment.computeIfAbsent(((Number) row.get("assignment_id")).longValue(), id -> new ArrayList<>())
                    .add(((Number) row.get("id")).longValue());
        }
        return byAssignment;
    }

    /** Takes the queue for the night grading run; false while another run is going. */
    boolean tryAcquireRun() {
        return running.compareAndSet(false, true);
    }

    void releaseRun() {
        nextRunAtMillis = System.currentTimeMillis() + intervalMs;
        running.set(false);
    }

    boolean isGradingNow(Long submissionId) {
        return gradingNow.contains(submissionId);
    }

    void recordNightGradeSeconds(double seconds) {
        avgNightGradeSeconds = avgNightGradeSeconds * 0.8 + seconds * 0.2;
    }

    /** Makes sure the assignment has a usable AI rubric, generating one if needed. */
    private boolean ensureAiRubric(Assignment assignment) {
        return ensureAiRubric(assignment, false);
    }

    /** {@code overnight} generates a missing rubric with the house model only. */
    boolean ensureAiRubric(Assignment assignment, boolean overnight) {
        if (AssignmentRubricService.isAiRubricReady(assignment.getAiRubric(), assignment.getPoints())) {
            return true;
        }
        String generated = overnight
                ? rubricService.generateRubric(assignment.getName(), assignment.getDescription(), null,
                        assignment.getPoints(), true)
                : rubricService.generateRubric(assignment.getName(), assignment.getDescription(), null,
                        assignment.getPoints());
        if (generated == null) {
            logger.info("AI re-grade: rubric for assignment {} still unavailable; will retry", assignment.getId());
            return false;
        }
        // Update only the rubric column so concurrent edits to the assignment aren't overwritten.
        jdbcTemplate.update("UPDATE assignment SET ai_rubric = ? WHERE id = ?", generated, assignment.getId());
        assignment.setAiRubric(generated);
        logger.info("AI re-grade: generated rubric for assignment {}", assignment.getId());
        return true;
    }

    /**
     * Grades one submission once. {@code flaggedOnly} is the background-job mode: skip it unless it
     * is still flagged, and clear the flag when it turns out not gradeable. Returns true if graded.

     */
    private boolean regrade(Long submissionId, Assignment assignment, boolean flaggedOnly) {
        GradingOutcome outcome = gradeOnce(submissionId, assignment, flaggedOnly, false);
        return outcome == GradingOutcome.AI_GRADE_APPLIED || outcome == GradingOutcome.MANUAL_OVERRIDE_KEPT;
    }

    /**
     * Grades one submission once and returns the outcome, or null when it was skipped or threw.
     * {@code flaggedOnly} skips it unless it is still flagged, and clears the flag when it turns out
     * not gradeable. {@code overnight} asks only the house model.
     */
    GradingOutcome gradeOnce(Long submissionId, Assignment assignment, boolean flaggedOnly, boolean overnight) {
        try {
            AssignmentSubmission submission = submissionRepo.findById(submissionId).orElse(null);
            if (submission == null || (flaggedOnly && !Boolean.TRUE.equals(submission.getNeedsAiRegrade()))) {
                return null;
            }
            // Loaded outside a session, so swap the lazy proxy for the already-loaded assignment.
            submission.setAssignment(assignment);
            long started = System.nanoTime();
            GradingResult result = overnight ? aiGrader.grade(submission, true) : aiGrader.grade(submission);
            double seconds = (System.nanoTime() - started) / 1e9;
            if (overnight) {
                recordNightGradeSeconds(seconds);
            } else {
                recordGradeSeconds(seconds);
            }
            switch (result.outcome()) {
                case AI_GRADE_APPLIED, MANUAL_OVERRIDE_KEPT -> logger.info("AI re-grade: submission {} -> {} ({}{})",
                        submissionId, result.state().aiGrade(), result.outcome(), overnight ? ", overnight" : "");
                case AI_NOT_GRADEABLE -> {
                    if (flaggedOnly) {
                        gradingService.clearAiRegradeFlag(submissionId, submission.currentRevision());
                    }
                }
                default -> {
                    // Failed (stays flagged for the next run) or revised meanwhile.
                }
            }
            return result.outcome();
        } catch (Exception e) {
            logger.warn("AI re-grade failed submission={} failureType={}", submissionId, e.getClass().getSimpleName());
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
