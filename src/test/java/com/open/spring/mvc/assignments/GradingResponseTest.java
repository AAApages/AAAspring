package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GradingResponseTest {

    @Test
    void everyOutcomeHasTheAgreedStatus() {
        Map<GradingOutcome, HttpStatus> expected = Map.ofEntries(
                Map.entry(GradingOutcome.MANUAL_GRADE_APPLIED, HttpStatus.OK),
                Map.entry(GradingOutcome.MANUAL_OVERRIDE_CLEARED, HttpStatus.OK),
                Map.entry(GradingOutcome.AI_GRADE_APPLIED, HttpStatus.OK),
                Map.entry(GradingOutcome.MANUAL_OVERRIDE_KEPT, HttpStatus.OK),
                Map.entry(GradingOutcome.REVISION_CREATED, HttpStatus.OK),
                Map.entry(GradingOutcome.CONTENT_UNCHANGED, HttpStatus.OK),
                Map.entry(GradingOutcome.NO_MANUAL_OVERRIDE, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.STALE_REVISION, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.INVALID_GRADE, HttpStatus.BAD_REQUEST),
                Map.entry(GradingOutcome.NOT_PERMITTED, HttpStatus.FORBIDDEN),
                Map.entry(GradingOutcome.AI_NOT_GRADEABLE, HttpStatus.UNPROCESSABLE_ENTITY),
                Map.entry(GradingOutcome.AI_FAILED, HttpStatus.BAD_GATEWAY),
                Map.entry(GradingOutcome.SUBMISSION_NOT_FOUND, HttpStatus.NOT_FOUND),
                // Closure and teacher reviews.
                Map.entry(GradingOutcome.ASSIGNMENT_CLOSED, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.ASSIGNMENT_FINALIZED, HttpStatus.OK),
                Map.entry(GradingOutcome.ASSIGNMENT_NOT_FOUND, HttpStatus.NOT_FOUND),
                Map.entry(GradingOutcome.LESSON_FEEDBACK_REQUIRED, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.REVIEW_REQUIRES_CLOSE, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.REVIEW_OPENED, HttpStatus.CREATED),
                Map.entry(GradingOutcome.REVIEW_ALREADY_OPEN, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.EVIDENCE_RECORDED, HttpStatus.OK),
                Map.entry(GradingOutcome.REVIEW_NOT_OPEN, HttpStatus.CONFLICT),
                Map.entry(GradingOutcome.REASON_REQUIRED, HttpStatus.BAD_REQUEST),
                Map.entry(GradingOutcome.DECISION_REQUIRED, HttpStatus.BAD_REQUEST),
                Map.entry(GradingOutcome.RATIONALE_REQUIRED, HttpStatus.BAD_REQUEST),
                Map.entry(GradingOutcome.REVIEW_NO_CHANGE, HttpStatus.OK),
                Map.entry(GradingOutcome.GRADE_CORRECTED, HttpStatus.OK),
                Map.entry(GradingOutcome.REVIEW_NOT_FOUND, HttpStatus.NOT_FOUND));

        assertEquals(GradingOutcome.values().length, expected.size(), "a new outcome needs an agreed status");
        expected.forEach((outcome, status) -> assertEquals(status, GradingResponse.statusOf(outcome), outcome.name()));
    }

    @Test
    void theBodyCarriesTheTypedOutcomeAndState() {
        var state = new SubmissionGradingState(1L, 2, 0.8, "fb", null, 0.6, "ai", 3, true, 0.8, "fb", false, null);

        var response = GradingResponse.from(GradingResult.of(GradingOutcome.MANUAL_OVERRIDE_KEPT, state));

        assertNotNull(response.getBody());
        assertEquals(GradingOutcome.MANUAL_OVERRIDE_KEPT, response.getBody().outcome());
        assertEquals(state, response.getBody().grading());
    }
}
