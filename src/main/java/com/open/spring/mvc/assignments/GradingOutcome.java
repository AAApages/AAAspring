package com.open.spring.mvc.assignments;

/**
 * Result of a grading, closing or review action. {@link GradingResponse} maps each to an HTTP
 * status; the frontends map each to display text.
 */
public enum GradingOutcome {
    MANUAL_GRADE_APPLIED,
    MANUAL_OVERRIDE_CLEARED,
    NO_MANUAL_OVERRIDE,
    AI_GRADE_APPLIED,
    /** The AI grade was saved, but the manual override stays effective. */
    MANUAL_OVERRIDE_KEPT,
    /** New content was saved as a new revision (also returned for a new submission). */
    REVISION_CREATED,
    CONTENT_UNCHANGED,
    /** The submission has a newer revision than the one graded or viewed; nothing was written. */
    STALE_REVISION,
    /** The grade is outside 0 to the assignment's points. */
    INVALID_GRADE,
    AI_NOT_GRADEABLE,
    AI_FAILED,
    /** The assignment is closed; grades change only through a teacher review. */
    ASSIGNMENT_CLOSED,
    /** The assignment was closed and its grades copied to the gradebook. */
    ASSIGNMENT_FINALIZED,
    /** A team-taught lesson's homework waits for the submitter's complete lesson feedback. */
    LESSON_FEEDBACK_REQUIRED,
    REVIEW_REQUIRES_CLOSE,
    REVIEW_OPENED,
    REVIEW_ALREADY_OPEN,
    EVIDENCE_RECORDED,
    REVIEW_NOT_OPEN,
    REASON_REQUIRED,
    DECISION_REQUIRED,
    RATIONALE_REQUIRED,
    REVIEW_NO_CHANGE,
    /** A review approved an adjustment, applied as an authorized correction. */
    GRADE_CORRECTED,
    REVIEW_NOT_FOUND,
    /** The caller may not do this; no state is returned. */
    NOT_PERMITTED,
    SUBMISSION_NOT_FOUND,
    ASSIGNMENT_NOT_FOUND
}
