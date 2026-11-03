package com.open.spring.mvc.lessonfeedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentAuthorizationService;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Feedback;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.LessonInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.SubmissionInput;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Summary;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackService.FeedbackView;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonRole;

/** Everyone writes only their own feedback, homework waits for it, and only teachers and graders see the summary. */
class LessonFeedbackServiceTest {

    private static final long ASSIGNMENT_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-10-29T17:00:00Z");

    private final LessonFeedbackJpaRepository repository = mock(LessonFeedbackJpaRepository.class);
    private final AssignmentJpaRepository assignmentRepository = mock(AssignmentJpaRepository.class);
    private final LessonFeedbackService service = new LessonFeedbackService(repository, assignmentRepository,
            new AssignmentAuthorizationService(), new LessonFeedbackRules(), Clock.fixed(NOW, ZoneOffset.UTC),
            new Random(7));

    private Person hop;
    private Person lex;
    private Assignment assignment;

    /** Person ids live on Submitter with no setter; the grader check compares ids. */
    private static Person person(Long id, String uid, String role) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        person.setRoles(new ArrayList<>(List.of(new PersonRole("ROLE_USER"), new PersonRole(role))));
        return person;
    }

    private static LessonFeedbackEntity row(String uid, Feedback feedback, long createdAt) {
        LessonFeedbackEntity row = new LessonFeedbackEntity();
        row.setId("row-" + uid);
        row.setVersion(0L);
        row.setAssignmentId(ASSIGNMENT_ID);
        row.setUid(uid);
        row.apply(feedback);
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt);
        return row;
    }

    private static HttpStatus statusOf(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        return HttpStatus.valueOf(error.getStatusCode().value());
    }

    @BeforeEach
    void setUp() {
        hop = person(5L, "hop", "ROLE_STUDENT");
        lex = person(6L, "lex", "ROLE_STUDENT");
        assignment = new Assignment("Loops", "auto-created", "Loops lesson", 1.0, "10/30/2026");
        assignment.setId(ASSIGNMENT_ID);
        assignment.setContentUrl("/csp/lessons/loops/");
        assignment.getCreators().add(lex);
        when(assignmentRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(assignment));
        when(repository.findByAssignmentIdAndUid(any(), any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(LessonFeedbackEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void lessonStepSavesTheSignedInStudentsRowWithTheServerTime() {
        FeedbackView view = service.saveLessonStep(hop, ASSIGNMENT_ID, new LessonInput(4, null, 3, " Clear examples ", null));

        assertEquals(new Feedback(4, null, 3, null, "Clear examples", null), view.feedback());
        assertEquals(Set.of("lesson", "popcorn"), view.locked());
        assertFalse(view.complete());
        assertEquals("/csp/lessons/loops/", view.page());
        assertEquals(NOW.toEpochMilli(), view.createdAt());
        assertEquals(NOW.toEpochMilli(), view.updatedAt());
        verify(repository).saveAndFlush(argThat(saved ->
                "hop".equals(saved.getUid()) && saved.getAssignmentId() == ASSIGNMENT_ID && saved.getId() != null));
    }

    @Test
    void laterSavesUpdateTheSameRowButCannotChangeASavedRating() {
        LessonFeedbackEntity saved = row("hop", new Feedback(4, null, null, null, "pros", null), 1_000L);
        when(repository.findByAssignmentIdAndUid(ASSIGNMENT_ID, "hop")).thenReturn(Optional.of(saved));

        assertEquals(HttpStatus.CONFLICT,
                statusOf(() -> service.saveLessonStep(hop, ASSIGNMENT_ID, new LessonInput(5, null, null, null, null))));

        FeedbackView view = service.saveLessonStep(hop, ASSIGNMENT_ID, new LessonInput(4, 2, null, null, "grows"));
        assertEquals(new Feedback(4, 2, null, null, "pros", "grows"), view.feedback());
        assertEquals(1_000L, view.createdAt());
        assertEquals(NOW.toEpochMilli(), view.updatedAt());
        assertEquals("row-hop", saved.getId());
    }

    @Test
    void submissionStepMustLeaveTheFeedbackComplete() {
        when(repository.findByAssignmentIdAndUid(ASSIGNMENT_ID, "hop"))
                .thenReturn(Optional.of(row("hop", new Feedback(4, null, 3, null, "pros", null), 1_000L)));

        assertEquals(HttpStatus.BAD_REQUEST, statusOf(() ->
                service.saveSubmissionStep(hop, ASSIGNMENT_ID, new SubmissionInput(null, 5, null, 2, null, null))));
        verify(repository, never()).saveAndFlush(any());

        FeedbackView view = service.saveSubmissionStep(hop, ASSIGNMENT_ID, new SubmissionInput(null, 5, null, 2, null, "grows"));
        assertTrue(view.complete());
        assertEquals(Set.of("lesson", "engage", "popcorn", "homework"), view.locked());
    }

    @Test
    void theTeachingTeamAndTeachersRateLikeEveryoneElse() {
        service.saveLessonStep(lex, ASSIGNMENT_ID, new LessonInput(5, 5, 5, null, null));
        service.saveLessonStep(person(3L, "niko", "ROLE_TEACHER"), ASSIGNMENT_ID, new LessonInput(4, null, null, null, null));

        verify(repository).saveAndFlush(argThat(saved -> "lex".equals(saved.getUid())));
        verify(repository).saveAndFlush(argThat(saved -> "niko".equals(saved.getUid())));
    }

    @Test
    void homeworkWaitsOnlyOnAnOpenTeamTaughtLessonWithUnfinishedFeedback() {
        assertFalse(service.isFeedbackMissing(hop, assignment), "not team-taught");

        assignment.setTeamTeach(true);
        assertTrue(service.isFeedbackMissing(hop, assignment), "nothing saved yet");
        when(repository.findByAssignmentIdAndUid(ASSIGNMENT_ID, "hop"))
                .thenReturn(Optional.of(row("hop", new Feedback(4, 4, 4, null, "pros", "grows"), 1_000L)));
        assertTrue(service.isFeedbackMissing(hop, assignment), "homework rating missing");
        when(repository.findByAssignmentIdAndUid(ASSIGNMENT_ID, "hop"))
                .thenReturn(Optional.of(row("hop", new Feedback(4, 4, 4, 3, "pros", "grows"), 1_000L)));
        assertFalse(service.isFeedbackMissing(hop, assignment), "complete");

        assertTrue(service.isFeedbackMissing(lex, assignment), "the teaching team rates too");
        assignment.setClosedAt(LocalDateTime.of(2026, 10, 28, 9, 0));
        assertFalse(service.isFeedbackMissing(lex, assignment), "closing refuses the homework on its own");
    }

    @Test
    void feedbackCannotChangeAfterTheAssignmentCloses() {
        assignment.setClosedAt(LocalDateTime.of(2026, 10, 28, 9, 0));

        assertEquals(HttpStatus.CONFLICT,
                statusOf(() -> service.saveLessonStep(hop, ASSIGNMENT_ID, new LessonInput(4, null, null, null, null))));
        assertTrue(service.mine(hop, ASSIGNMENT_ID).closed());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownAssignmentIsNotFound() {
        when(assignmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> service.mine(hop, 99L)));
        assertEquals(HttpStatus.NOT_FOUND,
                statusOf(() -> service.saveLessonStep(hop, 99L, new LessonInput(4, null, null, null, null))));
    }

    @Test
    void mineIsEmptyBeforeTheFirstSave() {
        FeedbackView view = service.mine(hop, ASSIGNMENT_ID);

        assertEquals(Feedback.EMPTY, view.feedback());
        assertTrue(view.locked().isEmpty());
        assertFalse(view.complete());
        assertNull(view.createdAt());
    }

    @Test
    void onlyTeachersAndGradersSeeTheSummary() {
        when(repository.findByAssignmentId(ASSIGNMENT_ID)).thenReturn(List.of());
        Person grader = person(8L, "ta", "ROLE_STUDENT");
        assignment.setAssignedGraders(new ArrayList<>(List.of(grader)));

        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.summary(hop, ASSIGNMENT_ID)));
        assertEquals(HttpStatus.FORBIDDEN, statusOf(() -> service.summary(lex, ASSIGNMENT_ID)), "the teaching team");
        assertEquals(0, service.summary(person(3L, "niko", "ROLE_TEACHER"), ASSIGNMENT_ID).responseCount());
        assertEquals(0, service.summary(grader, ASSIGNMENT_ID).responseCount());
    }

    @Test
    void theSummaryIsLiveFromTheFirstResponseBeforeClose() {
        when(repository.findByAssignmentId(ASSIGNMENT_ID))
                .thenReturn(List.of(row("hop", new Feedback(4, 3, 5, 2, "Clear", "Slower"), 1_000L)));

        Summary summary = service.summary(person(3L, "niko", "ROLE_TEACHER"), ASSIGNMENT_ID);

        assertNull(assignment.getClosedAt());
        assertEquals(1, summary.responseCount());
        assertEquals(2.0, summary.averageHomework());
        assertEquals(List.of(0, 0, 0, 0, 1), summary.counts().get("popcorn"));
        assertEquals(List.of("Clear"), summary.pros());
    }
}
