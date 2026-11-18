package com.open.spring.mvc.lessonfeedback;

import java.util.Map;
import java.util.function.Function;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.assignments.GradingResponse;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.LessonInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.SubmissionInput;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * REST API for lesson feedback under /api/lesson-feedback (see LessonFeedbackService).
 *
 * The student is always the signed-in account, never a field of the request. Rule
 * violations come back with their status and a body of {"error": "..."}, so the page can
 * show the message. Access follows the default /api/** rule in
 * SecurityConfig; the service decides who may see what.
 */
@RestController
@RequestMapping("/api/lesson-feedback")
public class LessonFeedbackApiRestController {

    static final String CONCURRENT_SAVE =
            "Your feedback was just saved from another tab. Reload to see it, then try again.";
    static final String UNREADABLE_BODY =
            "Send ratings as whole numbers from 1 to 5 and pros and grows as text.";

    private final LessonFeedbackService feedbackService;
    private final PersonJpaRepository personRepo;

    public LessonFeedbackApiRestController(LessonFeedbackService feedbackService, PersonJpaRepository personRepo) {
        this.feedbackService = feedbackService;
        this.personRepo = personRepo;
    }

    /** GET /api/lesson-feedback/{assignmentId}/mine - the student's own feedback, empty before the first save. */
    @GetMapping("/{assignmentId}/mine")
    public ResponseEntity<?> mine(@PathVariable Long assignmentId, @AuthenticationPrincipal UserDetails userDetails) {
        return respond(userDetails, student -> feedbackService.mine(student, assignmentId));
    }

    /**
     * PUT /api/lesson-feedback/{assignmentId}/lesson - the lesson step.
     * Body: {"lesson": 4, "engage": null, "popcorn": 3, "pros": "...", "grows": "..."}; null leaves a field as it is.
     */
    @PutMapping("/{assignmentId}/lesson")
    public ResponseEntity<?> saveLessonStep(@PathVariable Long assignmentId, @RequestBody LessonInput input,
            @AuthenticationPrincipal UserDetails userDetails) {
        return respond(userDetails, student -> feedbackService.saveLessonStep(student, assignmentId, input));
    }

    /** PUT /api/lesson-feedback/{assignmentId}/submission - the submission step, which adds "homework" and must be complete. */
    @PutMapping("/{assignmentId}/submission")
    public ResponseEntity<?> saveSubmissionStep(@PathVariable Long assignmentId, @RequestBody SubmissionInput input,
            @AuthenticationPrincipal UserDetails userDetails) {
        return respond(userDetails, student -> feedbackService.saveSubmissionStep(student, assignmentId, input));
    }

    /** GET /api/lesson-feedback/{assignmentId}/summary - live averages, rating counts and unnamed comments, for teachers and graders. */
    @GetMapping("/{assignmentId}/summary")
    public ResponseEntity<?> summary(@PathVariable Long assignmentId, @AuthenticationPrincipal UserDetails userDetails) {
        return respond(userDetails, viewer -> feedbackService.summary(viewer, assignmentId));
    }

    /**
     * A body that is not the expected JSON, such as text where a rating belongs, is the
     * sender's mistake: 400 with a message, not the app-wide 500 from ExceptionHandlerAdvice.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(Map.of("error", UNREADABLE_BODY));
    }

    private ResponseEntity<?> respond(UserDetails userDetails, Function<Person, Object> action) {
        return GradingResponse.signedIn(personRepo, userDetails, person -> {
            try {
                return ResponseEntity.ok(action.apply(person));
            } catch (ResponseStatusException error) {
                String reason = error.getReason() == null ? error.getStatusCode().toString() : error.getReason();
                return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", reason));
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException error) {
                // Two first saves race on the unique (assignment, uid) row, or two edits on its version.
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", CONCURRENT_SAVE));
            }
        });
    }
}
