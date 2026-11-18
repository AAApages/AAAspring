package com.open.spring.mvc.assignments;

import java.util.Map;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** Response body for the grading endpoints. */
public record GradingResponse(GradingOutcome outcome, SubmissionGradingState grading, String detail) {

    public static ResponseEntity<GradingResponse> from(GradingResult result) {
        return ResponseEntity.status(statusOf(result.outcome()))
                .body(new GradingResponse(result.outcome(), result.state(), result.detail()));
    }

    /** Runs the action as the signed-in person, or answers 401 without calling it. */
    public static ResponseEntity<?> signedIn(PersonJpaRepository personRepo, UserDetails userDetails,
            Function<Person, ResponseEntity<?>> action) {
        Person person = userDetails == null ? null : personRepo.findByUid(userDetails.getUsername());
        if (person == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        return action.apply(person);
    }

    /** HTTP status for each outcome, shared by the grading, review and closure endpoints. */
    public static HttpStatus statusOf(GradingOutcome outcome) {
        return switch (outcome) {
            case MANUAL_GRADE_APPLIED, MANUAL_OVERRIDE_CLEARED, AI_GRADE_APPLIED, MANUAL_OVERRIDE_KEPT,
                    REVISION_CREATED, CONTENT_UNCHANGED, ASSIGNMENT_FINALIZED, EVIDENCE_RECORDED,
                    REVIEW_NO_CHANGE, GRADE_CORRECTED -> HttpStatus.OK;
            case REVIEW_OPENED -> HttpStatus.CREATED;
            case STALE_REVISION, NO_MANUAL_OVERRIDE, ASSIGNMENT_CLOSED, REVIEW_REQUIRES_CLOSE,
                    REVIEW_ALREADY_OPEN, REVIEW_NOT_OPEN, LESSON_FEEDBACK_REQUIRED -> HttpStatus.CONFLICT;
            case INVALID_GRADE, REASON_REQUIRED, DECISION_REQUIRED, RATIONALE_REQUIRED -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case AI_NOT_GRADEABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AI_FAILED -> HttpStatus.BAD_GATEWAY;
            case SUBMISSION_NOT_FOUND, REVIEW_NOT_FOUND, ASSIGNMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
