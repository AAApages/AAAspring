package com.open.spring.mvc.lessonfeedback;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentAuthorizationService;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Feedback;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.LessonInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.SubmissionInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Summary;
import com.open.spring.mvc.person.Person;

/**
 * Saves and reads lesson feedback, applying LessonFeedbackRules to every write.
 *
 * Feedback belongs to an assignment: its contentUrl is the lesson page, its teamTeach flag
 * says the homework waits for feedback, and its closedAt makes feedback final. Everyone
 * who submits the homework rates the lesson, the teaching team and teachers included, and
 * reads and writes only their own row. The Lesson Summary is live and goes to teachers and
 * the assignment's graders only. This is the only class that uses
 * LessonFeedbackJpaRepository; rule violations are ResponseStatusExceptions, as in the rules.
 */
@Service
public class LessonFeedbackService {

    /**
     * One student's feedback as their own form shows it. Every field is present before
     * anything is saved, so the form can draw the empty state from the same shape.
     *
     * @param page the lesson page (the assignment's contentUrl)
     * @param closed true once the assignment is finalized; feedback can no longer change
     * @param locked the ratings already saved, which can no longer change
     * @param createdAt epoch milliseconds of the first save, or null before it
     */
    public record FeedbackView(Long assignmentId, String page, boolean closed,
            Feedback feedback, Set<String> locked, boolean complete, Long createdAt, Long updatedAt) {
    }

    private static final Logger logger = LoggerFactory.getLogger(LessonFeedbackService.class);

    private final LessonFeedbackJpaRepository repository;
    private final AssignmentJpaRepository assignmentRepository;
    private final AssignmentAuthorizationService authorization;
    private final LessonFeedbackRules rules;
    private final Clock clock;
    private final Random random;

    @Autowired
    public LessonFeedbackService(LessonFeedbackJpaRepository repository, AssignmentJpaRepository assignmentRepository,
            AssignmentAuthorizationService authorization, LessonFeedbackRules rules) {
        this(repository, assignmentRepository, authorization, rules, Clock.systemUTC(), new SecureRandom());
    }

    /** For tests: a fixed clock for timestamps and a seeded Random for the summary's shuffle. */
    LessonFeedbackService(LessonFeedbackJpaRepository repository, AssignmentJpaRepository assignmentRepository,
            AssignmentAuthorizationService authorization, LessonFeedbackRules rules, Clock clock, Random random) {
        this.repository = repository;
        this.assignmentRepository = assignmentRepository;
        this.authorization = authorization;
        this.rules = rules;
        this.clock = clock;
        this.random = random;
    }

    /** The student's own feedback on this assignment's lesson, empty when nothing is saved yet. */
    @Transactional(readOnly = true)
    public FeedbackView mine(Person student, Long assignmentId) {
        Assignment assignment = findAssignment(assignmentId);
        LessonFeedbackEntity row = repository.findByAssignmentIdAndUid(assignmentId, student.getUid()).orElse(null);
        return view(assignment, row);
    }

    /** Saves the lesson step: ratings may be partial, saved ratings stay as they are. */
    @Transactional
    public FeedbackView saveLessonStep(Person student, Long assignmentId, LessonInput input) {
        return save(student, assignmentId, saved -> rules.mergeLessonPhase(saved, input));
    }

    /** Saves the submission step, which must leave the feedback complete. */
    @Transactional
    public FeedbackView saveSubmissionStep(Person student, Long assignmentId, SubmissionInput input) {
        return save(student, assignmentId, saved -> rules.mergeSubmissionPhase(saved, input));
    }

    /** The live Lesson Summary, for teachers, admins and the assignment's assigned graders. */
    @Transactional(readOnly = true)
    public Summary summary(Person viewer, Long assignmentId) {
        Assignment assignment = findAssignment(assignmentId);
        // Not authorization.canManage: that also admits the creators, who taught the lesson.
        if (!authorization.isTeacherOrAdmin(viewer) && !authorization.isAssignedGrader(viewer, assignment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only teachers and graders can see the Lesson Summary.");
        }
        List<Feedback> records = repository.findByAssignmentId(assignmentId).stream()
                .map(LessonFeedbackEntity::toFeedback)
                .toList();
        return rules.summarize(records, random);
    }

    /**
     * True when this homework must wait: the lesson is team-taught, the assignment is still
     * open, and the person submitting has not finished their feedback. A closed assignment
     * refuses the submission on its own, and feedback can't be saved after close.
     */
    @Transactional(readOnly = true)
    public boolean isFeedbackMissing(Person submitter, Assignment assignment) {
        if (submitter == null || assignment == null || !Boolean.TRUE.equals(assignment.getTeamTeach())
                || assignment.getClosedAt() != null) {
            return false;
        }
        return repository.findByAssignmentIdAndUid(assignment.getId(), submitter.getUid())
                .map(row -> !rules.isComplete(row.toFeedback()))
                .orElse(true);
    }

    private FeedbackView save(Person student, Long assignmentId, UnaryOperator<Feedback> merge) {
        Assignment assignment = findAssignment(assignmentId);
        if (assignment.getClosedAt() != null) {
            // Closing makes the grades final, and the feedback beside them final too.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Feedback closed when this assignment was finalized.");
        }
        long now = clock.millis();
        LessonFeedbackEntity row = repository.findByAssignmentIdAndUid(assignmentId, student.getUid())
                .orElseGet(() -> newRow(assignmentId, student.getUid(), now));
        Feedback merged = merge.apply(row.toFeedback());
        row.apply(merged);
        row.setUpdatedAt(now);
        // Flushed here so a racing save fails in this call, where the controller can answer 409.
        LessonFeedbackEntity saved = repository.saveAndFlush(row);
        // No uid here: not even the logs should tie feedback to a student.
        logger.info("Lesson feedback saved for assignment {} (complete={})", assignmentId, rules.isComplete(merged));
        return view(assignment, saved);
    }

    private static LessonFeedbackEntity newRow(Long assignmentId, String uid, long now) {
        LessonFeedbackEntity row = new LessonFeedbackEntity();
        row.setId(UUID.randomUUID().toString());
        row.setAssignmentId(assignmentId);
        row.setUid(uid);
        row.setCreatedAt(now);
        return row;
    }

    private Assignment findAssignment(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found."));
    }

    private FeedbackView view(Assignment assignment, LessonFeedbackEntity row) {
        Feedback feedback = row == null ? Feedback.EMPTY : row.toFeedback();
        return new FeedbackView(assignment.getId(), assignment.getContentUrl(), assignment.getClosedAt() != null,
                feedback, rules.lockedFields(feedback), rules.isComplete(feedback),
                row == null ? null : row.getCreatedAt(), row == null ? null : row.getUpdatedAt());
    }
}
