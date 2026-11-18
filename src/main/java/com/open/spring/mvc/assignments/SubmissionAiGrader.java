package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.aiconnections.AiHouseSettingsRepository;
import com.open.spring.mvc.person.Person;

/**
 * Calls the AI grader outside any transaction and records the result for the revision it graded.
 * Callers pass a submission whose assignment is loaded, since the grader reads its rubric.
 */
@Service
public class SubmissionAiGrader {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionAiGrader.class);

    private final AssignmentAiGradingService aiGradingService;
    private final SubmissionGradingService gradingService;
    private final GradeReviewService reviewService;
    private final AiHouseSettingsRepository nightSettings;

    public SubmissionAiGrader(AssignmentAiGradingService aiGradingService, SubmissionGradingService gradingService,
            GradeReviewService reviewService) {
        this(aiGradingService, gradingService, reviewService, null);
    }

    @Autowired
    public SubmissionAiGrader(AssignmentAiGradingService aiGradingService, SubmissionGradingService gradingService,
            GradeReviewService reviewService, @Nullable AiHouseSettingsRepository nightSettings) {
        this.aiGradingService = aiGradingService;
        this.gradingService = gradingService;
        this.reviewService = reviewService;
        this.nightSettings = nightSettings;
    }

    /** Grades the current revision with the cloud models and copies the saved state onto the entity. */
    public GradingResult grade(AssignmentSubmission submission) {
        return grade(submission, false);
    }

    /**
     * Grades the current revision and copies the saved state onto the entity. {@code overnight}
     * asks only the self-hosted model (night grading).
     */
    public GradingResult grade(AssignmentSubmission submission, boolean overnight) {
        int revision = submission.currentRevision();
        SubmissionGradingState before = gradingService.currentState(submission.getId()).orElse(null);
        if (before == null) {
            return GradingResult.notFound();
        }
        if (before.closed()) {
            return GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, before);
        }
        AssignmentAiGradingService.GradeResult result = callAi(submission, overnight);
        if (!result.isGraded()) {
            return new GradingResult(failureOutcome(result),
                    gradingService.currentState(submission.getId()).orElse(null), result.message());
        }
        GradingResult recorded = gradingService.recordAiGrade(submission.getId(), revision, result);
        if (recorded.state() != null) {
            recorded.state().copyTo(submission);
        }
        logger.info("AI grade for submission={} revision={}: {}", submission.getId(), revision, recorded.outcome());
        return recorded;
    }

    /**
     * Grades right after a submit or revision; a failure queues the revision for a retry. During
     * the night grading window it only queues the revision, so no paid model is called at night.
     */
    public GradingResult autoGrade(AssignmentSubmission submission) {
        if (nightSettings != null && nightSettings.isNightWindowOpen()) {
            if (gradingService.flagForAiRegrade(submission.getId(), submission.currentRevision())) {
                gradingService.refresh(submission);
            }
            logger.info("AI grade for submission={} queued for night grading", submission.getId());
            return new GradingResult(GradingOutcome.AI_FAILED,
                    gradingService.currentState(submission.getId()).orElse(null), "Queued for night grading.");
        }
        GradingResult result = grade(submission);
        if (result.outcome() == GradingOutcome.AI_FAILED
                && gradingService.flagForAiRegrade(submission.getId(), submission.currentRevision())) {
            gradingService.refresh(submission);
        }
        return result;
    }

    /** Re-evaluates a submission under review as evidence; the grade itself is not changed. */
    public GradeReviewService.ReviewResult evaluateForReview(Long reviewId, Person teacher) {
        GradeReviewService.AiEvaluationTarget target = reviewService.aiEvaluationTarget(reviewId, teacher);
        if (target.refusal() != null) {
            return GradeReviewService.ReviewResult.of(target.refusal());
        }
        AssignmentAiGradingService.GradeResult result = callAi(target.submission(), false);
        if (!result.isGraded()) {
            return new GradeReviewService.ReviewResult(failureOutcome(result), target.review(), null, result.message());
        }
        return reviewService.recordAiEvaluation(reviewId, teacher, result);
    }

    private AssignmentAiGradingService.GradeResult callAi(AssignmentSubmission submission, boolean overnight) {
        try {
            return overnight ? aiGradingService.grade(submission, true) : aiGradingService.grade(submission);
        } catch (Exception e) {
            logger.warn("AI grading failed submission={} failureType={}", submission.getId(), e.getClass().getSimpleName());
            return AssignmentAiGradingService.GradeResult.failed(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static GradingOutcome failureOutcome(AssignmentAiGradingService.GradeResult result) {
        return "not_gradeable".equals(result.status()) ? GradingOutcome.AI_NOT_GRADEABLE : GradingOutcome.AI_FAILED;
    }
}
