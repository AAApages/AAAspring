package com.open.spring.mvc.assignments;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.lessonfeedback.LessonFeedbackService;
import com.open.spring.mvc.person.Person;

/**
 * Keeps one submission per assignment and submitter (a person or a group): a resubmit becomes the
 * next revision of the existing submission (SubmissionGradingService.revise), so the grading history
 * holds every earlier version and the row keeps its id, graders and AI queue entry.
 *
 * Rows saved before this existed were never revised: a resubmit added another row. Those rows are
 * left as they are. The newest row per assignment and submitter is the current submission and the
 * older ones count as its earlier versions; see {@link #latestOnly} and {@link #currentRowSql}.
 */
@Service
public class AssignmentSubmissionHistoryService {

    private final AssignmentSubmissionJPA submissionRepo;
    private final SubmissionGradingService gradingService;
    private final LessonFeedbackService lessonFeedback;
    // Two submits for the same assignment at once (a double click, or a multi-file upload) must not
    // both see "no submission yet" and insert two rows, so the find-then-save step runs one at a time.
    private final Object submitLock = new Object();

    public AssignmentSubmissionHistoryService(AssignmentSubmissionJPA submissionRepo,
            SubmissionGradingService gradingService, LessonFeedbackService lessonFeedback) {
        this.submissionRepo = submissionRepo;
        this.gradingService = gradingService;
        this.lessonFeedback = lessonFeedback;
    }

    /**
     * SQL condition that is true when {@code alias} is the current submission, i.e. no newer row
     * exists for the same assignment and submitter. For queries over assignment_submission.
     */
    public static String currentRowSql(String alias) {
        return "NOT EXISTS (SELECT 1 FROM assignment_submission newer WHERE newer.assignment_id = "
                + alias + ".assignment_id AND newer.submitter_id = " + alias + ".submitter_id AND newer.id > "
                + alias + ".id)";
    }

    /**
     * The grading service's result, the submission it applies to (with the draft's loaded
     * assignment), and whether that was an existing submission rather than a new one.
     */
    public record SubmitResult(GradingResult result, AssignmentSubmission submission, boolean replaced) {
    }

    /** Whether {@link #submit} would refuse this person's homework for missing lesson feedback. */
    public boolean isLessonFeedbackMissing(Person submittedBy, Assignment assignment) {
        return lessonFeedback.isFeedbackMissing(submittedBy, assignment);
    }

    /**
     * Saves {@code draft} as the submitter's submission for its assignment: a new submission if
     * there is none yet, otherwise the draft's work becomes the next revision of the existing one.
     * A team-taught lesson's homework is refused until the person submitting finishes their
     * lesson feedback, the same rule the Pages submit buttons apply.
     */
    public SubmitResult submit(AssignmentSubmission draft, Person submittedBy) {
        if (isLessonFeedbackMissing(submittedBy, draft.getAssignment())) {
            return new SubmitResult(GradingResult.of(GradingOutcome.LESSON_FEEDBACK_REQUIRED, null), draft, false);
        }
        synchronized (submitLock) {
            List<AssignmentSubmission> existing = rowsNewestFirst(key(draft));
            if (existing.isEmpty()) {
                return new SubmitResult(gradingService.submit(draft, submittedBy), draft, false);
            }
            AssignmentSubmission current = existing.get(0);
            current.setAssignment(draft.getAssignment());
            current.setComment(draft.getComment());
            current.setIsLate(draft.getIsLate());
            current.setTechnicalExcellence(draft.getTechnicalExcellence());
            current.setCommunication(draft.getCommunication());
            current.setWorkHabits(draft.getWorkHabits());
            current.setAiOrchestration(draft.getAiOrchestration());
            current.setSelfAssessmentReflection(draft.getSelfAssessmentReflection());
            return new SubmitResult(gradingService.revise(current, draft.getContent(), submittedBy), current, true);
        }
    }

    /** Keeps the newest row per assignment and submitter, in the given order. */
    public static List<AssignmentSubmission> latestOnly(Collection<AssignmentSubmission> submissions) {
        Map<List<Long>, AssignmentSubmission> newest = new LinkedHashMap<>();
        for (AssignmentSubmission submission : submissions) {
            List<Long> key = key(submission);
            if (key != null) {
                newest.merge(key, submission, (a, b) -> a.getId() >= b.getId() ? a : b);
            }
        }
        return submissions.stream()
                .filter(submission -> key(submission) == null || newest.get(key(submission)) == submission)
                .toList();
    }

    /** Assignment and submitter ids, or null when either is missing (such a row is always its own). */
    public static List<Long> key(AssignmentSubmission submission) {
        // getId() on the lazy assignment proxy doesn't load it.
        Long assignmentId = submission.getAssignment() != null ? submission.getAssignment().getId() : submission.getAssignmentid();
        Long submitterId = submission.getSubmitter() == null ? null : submission.getSubmitter().getId();
        return assignmentId == null || submitterId == null ? null : Arrays.asList(assignmentId, submitterId);
    }

    /** Every row for one assignment and submitter, newest first (several only in data from before revisions). */
    private List<AssignmentSubmission> rowsNewestFirst(List<Long> key) {
        return submissionRepo.findBySubmitterId(key.get(1)).stream()
                .filter(row -> key.equals(key(row)))
                .sorted(Comparator.comparing(AssignmentSubmission::getId).reversed())
                .toList();
    }

    /**
     * One version of a submission. {@code current} marks the latest; {@code revision} is set for
     * versions of the same submission, and earlier rows from before revisions carry their own
     * {@code submissionId} instead. Comment and late status are only known for whole rows.
     */
    public record Version(Long submissionId, Integer revision, boolean current, LocalDateTime submittedAt,
            LocalDateTime replacedAt, Map<String, Object> content, String comment, Boolean isLate, Double grade,
            String feedback, String aiSummary, Integer qualityScore, LocalDateTime gradedAt) {
    }

    /**
     * Every version of a submission, the current one first, then newest to oldest. {@code closed}
     * means the assignment is closed, so no version can be restored.
     */
    public record SubmissionHistory(Long submissionId, Long assignmentId, String assignmentName, Double points,
            List<Version> versions, boolean closed) {
    }

    /**
     * The current submission for {@code submission}'s assignment and submitter. Usually the
     * submission itself; for an older duplicate row, the newer row that superseded it.
     */
    public AssignmentSubmission currentOf(AssignmentSubmission submission) {
        List<Long> key = key(submission);
        return key == null ? submission : rowsNewestFirst(key).stream().findFirst().orElse(submission);
    }

    /**
     * The versions of the current submission {@code current} (see {@link #currentOf}): the current
     * revision, earlier revisions from the grading history, then older rows for the same assignment.
     */
    public SubmissionHistory historyOf(AssignmentSubmission current) {
        List<SubmissionGradeEventEntity> events = gradingService.history(current.getId());
        List<Version> versions = new ArrayList<>();
        versions.add(versionOf(current, true, submittedAt(events, current.currentRevision())));
        for (int revision = current.currentRevision() - 1; revision >= AssignmentSubmission.FIRST_REVISION; revision--) {
            Version earlier = revisionOf(current.getId(), revision, events);
            if (earlier != null) {
                versions.add(earlier);
            }
        }
        List<Long> key = key(current);
        if (key != null) {
            rowsNewestFirst(key).stream()
                    .filter(older -> older.getId() < current.getId())
                    .forEach(older -> versions.add(versionOf(older, false, null)));
        }
        Assignment assignment = current.getAssignment();
        return new SubmissionHistory(current.getId(), assignment == null ? null : assignment.getId(),
                assignment == null ? "(deleted assignment)" : assignment.getName(),
                assignment == null ? null : assignment.getPoints(), versions,
                assignment != null && assignment.isClosed());
    }

    /**
     * The content of one earlier version of {@code current}, named by the {@link Version}'s
     * submissionId and revision (revision is null for an older duplicate row). Empty if there is no
     * such earlier version. Restoring submits this content again as a new revision, so the history
     * is never rewritten.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> earlierVersionContent(Long currentId, Long versionSubmissionId, Integer revision) {
        return submissionRepo.findById(currentId)
                .map(this::historyOf)
                .flatMap(history -> history.versions().stream()
                        .filter(version -> !version.current())
                        .filter(version -> Objects.equals(version.submissionId(), versionSubmissionId)
                                && Objects.equals(version.revision(), revision))
                        .findFirst())
                .map(Version::content);
    }

    /**
     * An earlier revision from the grading history: the content it was submitted with and the last
     * grade that was effective for it. Null if the history has no content for that revision.
     */
    private static Version revisionOf(Long submissionId, int revision, List<SubmissionGradeEventEntity> events) {
        SubmissionGradeEventEntity submitted = null;
        SubmissionGradeEventEntity graded = null;
        for (SubmissionGradeEventEntity event : events) {
            if (event.getRevision() == revision && startsRevision(event)) {
                submitted = event;
            } else if (event.getRevision() == revision && Boolean.TRUE.equals(event.getEffective())) {
                graded = event;
            }
        }
        if (submitted == null) {
            return null;
        }
        return new Version(submissionId, revision, false, submittedAt(events, revision), submittedAt(events, revision + 1),
                submitted.getContent(), null, null, graded == null ? null : graded.getGrade(),
                graded == null ? null : graded.getFeedback(), null, null,
                graded == null ? null : graded.getCreatedAt());
    }

    private static boolean startsRevision(SubmissionGradeEventEntity event) {
        return event.getType() == SubmissionGradeEventEntity.Type.SUBMITTED || event.getType() == SubmissionGradeEventEntity.Type.REVISED;
    }

    /**
     * When the revision was submitted, or null if unknown. A first submission with no actor was
     * recorded later, by its first revision (see SubmissionGradingService.revise), so its time isn't known.
     */
    private static LocalDateTime submittedAt(List<SubmissionGradeEventEntity> events, int revision) {
        return events.stream()
                .filter(event -> event.getRevision() == revision && startsRevision(event))
                .filter(event -> !(event.getType() == SubmissionGradeEventEntity.Type.SUBMITTED && event.getActorId() == null))
                .map(SubmissionGradeEventEntity::getCreatedAt)
                .reduce((first, last) -> last)
                .orElse(null);
    }

    private static Version versionOf(AssignmentSubmission submission, boolean current, LocalDateTime submittedAt) {
        return new Version(submission.getId(), current ? submission.currentRevision() : null, current, submittedAt, null,
                submission.getContent(), submission.getComment(), submission.getIsLate(), submission.getGrade(),
                submission.getFeedback(), submission.getAiSummary(), submission.getQualityScore(),
                submission.getGradedAt());
    }

    /** Number of earlier versions: earlier revisions plus older rows for the same assignment and submitter. */
    public static int earlierVersionCount(AssignmentSubmission current, Collection<AssignmentSubmission> allRows) {
        List<Long> key = key(current);
        long olderRows = key == null ? 0
                : allRows.stream().filter(row -> key.equals(key(row)) && !Objects.equals(row.getId(), current.getId())).count();
        return current.currentRevision() - AssignmentSubmission.FIRST_REVISION + (int) olderRows;
    }
}
