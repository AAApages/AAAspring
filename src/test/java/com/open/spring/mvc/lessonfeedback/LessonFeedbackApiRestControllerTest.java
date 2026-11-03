package com.open.spring.mvc.lessonfeedback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Feedback;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.LessonInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.SubmissionInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackService.FeedbackView;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/** The student comes from the login, and every failure reaches the page as a status with a message. */
class LessonFeedbackApiRestControllerTest {

    private final LessonFeedbackService service = mock(LessonFeedbackService.class);
    private final PersonJpaRepository personRepo = mock(PersonJpaRepository.class);
    private final LessonFeedbackApiRestController controller = new LessonFeedbackApiRestController(service, personRepo);

    private Person hop;
    private UserDetails login;

    @BeforeEach
    void setUp() {
        hop = new Person();
        hop.setUid("hop");
        when(personRepo.findByUid("hop")).thenReturn(hop);
        login = User.withUsername("hop").password("unused").authorities("ROLE_USER").build();
    }

    private static FeedbackView emptyView() {
        return new FeedbackView(10L, "/csp/lessons/loops/", false, Feedback.EMPTY, Set.of(), false, null, null);
    }

    @Test
    void signedOutRequestsAreRefusedWithoutReachingTheService() {
        ResponseEntity<?> response = controller.saveLessonStep(10L, new LessonInput(4, null, null, null, null), null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void theSignedInStudentsFeedbackIsReturned() {
        FeedbackView view = emptyView();
        when(service.mine(same(hop), eq(10L))).thenReturn(view);

        ResponseEntity<?> response = controller.mine(10L, login);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(view, response.getBody());
    }

    @Test
    void aBrokenRuleKeepsItsStatusAndMessage() {
        when(service.saveSubmissionStep(same(hop), eq(10L), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing: grows"));

        ResponseEntity<?> response = controller.saveSubmissionStep(10L,
                new SubmissionInput(4, 4, 4, 4, "pros", null), login);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", "Missing: grows"), response.getBody());
    }

    @Test
    void anUnreadableBodyIsTheSendersMistakeNotAServerError() throws Exception {
        java.lang.reflect.Method handler = LessonFeedbackApiRestController.class
                .getMethod("unreadableBody", HttpMessageNotReadableException.class);
        assertArrayEquals(new Class<?>[] {HttpMessageNotReadableException.class},
                handler.getAnnotation(ExceptionHandler.class).value());

        ResponseEntity<Map<String, String>> response = controller.unreadableBody(
                new HttpMessageNotReadableException("Cannot deserialize Integer", mock(HttpInputMessage.class)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("error", LessonFeedbackApiRestController.UNREADABLE_BODY), response.getBody());
    }

    @Test
    void racingSavesAnswerConflictWithAReloadHint() {
        when(service.saveLessonStep(same(hop), eq(10L), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(LessonFeedbackEntity.class, "row-hop"))
                .thenThrow(new DataIntegrityViolationException("uk_lesson_feedback_assignment_uid"));
        LessonInput input = new LessonInput(4, null, null, null, null);

        for (int attempt = 0; attempt < 2; attempt++) {
            ResponseEntity<?> response = controller.saveLessonStep(10L, input, login);
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertEquals(Map.of("error", LessonFeedbackApiRestController.CONCURRENT_SAVE), response.getBody());
        }
    }
}
