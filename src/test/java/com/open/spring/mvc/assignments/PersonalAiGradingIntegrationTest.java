package com.open.spring.mvc.assignments;

import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.aiconnections.AiGradingRouter;
import com.open.spring.mvc.aiconnections.AiProviderFailure;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalAiGradingIntegrationTest {
    private final AiGradingRouter router = mock(AiGradingRouter.class);
    private final AssignmentAiGradingService grader = new AssignmentAiGradingService(
        new ObjectMapper(), mock(FileHandler.class), router, "https://api.github.com", "", "");

    @Test void personalKeyRoutingPreservesUpstreamWeightedInstructionsAndCalculation() throws Exception {
        Person author = student(1L, "alice");
        Assignment assignment = assignment(1L, "weighted");
        assignment.setPoints(1.0);
        assignment.setHackWeights(AssignmentRubricService.hackWeightsJson(
            "Hack #1 (Weight: 60%) Add lives and reflections. Hack #2 (Weight: 40%) Add a fourth button."));
        var submission = submission(1L, assignment);
        submission.setSubmitter(author);
        submission.setContent(Map.of("type", "text", "text", "Unmodified starter code"));
        when(router.generate(anyString(), same(author), eq(2))).thenReturn("""
            {"score":0.9,"quality":2,"feedback":"Required changes are missing.",
             "hacks":[{"hack":"Hack #1","score":0.15},{"hack":"Hack #2","score":0.4}]}
            """);
        var result = grader.grade(submission);
        assertEquals("graded", result.status());
        assertEquals(0.25, result.grade());
        assertTrue(result.feedback().contains("Hack #1 (60%): 0.15/1"));
        verify(router).generate(argThat(prompt -> prompt.contains("Add lives and reflections")
            && prompt.contains("Add a fourth button") && prompt.contains("does not attempt scores 0")), same(author), eq(2));
    }

    @Test void personalKeyResponseWithoutRequiredHackScoresCannotBecomeAGrade() throws Exception {
        var assignment = assignment(1L, "weighted");
        assignment.setHackWeights(AssignmentRubricService.hackWeightsJson(
            "Hack #1 (Weight: 60%) Add lives. Hack #2 (Weight: 40%) Add a button."));
        var submission = submission(1L, assignment);
        submission.setSubmitter(student(1L, "alice"));
        submission.setContent(Map.of("type", "text", "text", "Starter code"));
        when(router.generate(anyString(), any(), eq(2))).thenReturn(
            "{\"score\":0.9,\"quality\":4,\"feedback\":\"Looks good.\"}");
        assertEquals("failed", grader.grade(submission).status());
        assertNull(submission.getGrade());
    }
    @Test void compactSizedResponseStillRequiresAllSevenScoresAndUsesUpstreamWeights() throws Exception {
        var mapper = new ObjectMapper();
        var hacks = java.util.stream.IntStream.rangeClosed(1, 7)
            .mapToObj(i -> Map.of("hack", "Hack #" + i, "weight", 1, "instructions", "Task " + i)).toList();
        var scores = java.util.stream.IntStream.rangeClosed(1, 7)
            .mapToObj(i -> Map.of("hack", "Hack #" + i, "score", 0.5)).toList();
        var assignment = assignment(1L, "many tasks");
        assignment.setPoints(1.0);
        assignment.setHackWeights(mapper.writeValueAsString(hacks));
        var submission = submission(1L, assignment);
        submission.setContent(Map.of("type", "text", "text", "Student work"));
        when(router.generate(anyString(), isNull(), eq(7))).thenReturn(mapper.writeValueAsString(
            Map.of("score", 0.9, "quality", 3, "feedback", "Some tasks completed.", "hacks", scores)));
        var result = grader.grade(submission);
        assertEquals("graded", result.status());
        assertEquals(0.5, result.grade());
        assertTrue(result.feedback().contains("Hack #7"));
        when(router.generate(anyString(), isNull(), eq(7))).thenReturn(mapper.writeValueAsString(
            Map.of("score", 0.9, "quality", 3, "feedback", "Some tasks completed.", "hacks", scores.subList(0, 6))));
        assertEquals("failed", grader.grade(submission).status());
    }

    @Test void gradingKeepsRubricScalingAndUsesPersistedSubmissionAuthor() throws Exception {
        Person author = student(1L, "alice");
        Assignment assignment = assignment(1L, "test"); assignment.setPoints(10.0);
        AssignmentSubmission submission = submission(1L, assignment);
        submission.setSubmitter(author); submission.setContent(Map.of("type", "text", "text", "Student answer"));
        when(router.generate(anyString(), same(author), eq(0))).thenReturn("{\"score\":0.8,\"quality\":3,\"feedback\":\"Clear answer. Add detail.\"}");
        var result = grader.grade(submission);
        assertEquals("graded", result.status()); assertEquals(8.0, result.grade());
        assertEquals(3, result.qualityScore()); assertTrue(result.usedDefaultRubric());
        verify(router).generate(contains("Student answer"), same(author), eq(0));
    }
    @Test void invalidGradingOutputAndProviderExhaustionDoNotInventAGrade() throws Exception {
        AssignmentSubmission submission = submission(1L, assignment(1L, "test"));
        submission.setContent(Map.of("type", "text", "text", "answer"));
        when(router.generate(anyString(), isNull(), eq(0))).thenReturn("{\"score\":99,\"quality\":3,\"feedback\":\"wrong\"}");
        assertEquals("failed", grader.grade(submission).status());
        doThrow(new AiProviderFailure(true, "Providers unavailable")).when(router).generate(anyString(), isNull(), eq(0));
        assertEquals("failed", grader.grade(submission).status()); assertNull(submission.getGrade());
    }
    @Test void ungradeableContentNeverConsumesKeys() throws Exception {
        var submission = submission(1L, assignment(1L, "test")); submission.setContent(Map.of());
        assertEquals("not_gradeable", grader.grade(submission).status()); verifyNoInteractions(router);
    }
    @Test void rejectedIdentityCannotSaveOrTriggerGrading() {
        var controller = new AssignmentSubmissionAPIController();
        var people = mock(PersonJpaRepository.class); var assignments = mock(AssignmentJpaRepository.class);
        var submissions = mock(AssignmentSubmissionJPA.class); var ai = mock(AssignmentAiGradingService.class);
        ReflectionTestUtils.setField(controller, "personRepo", people);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignments);
        ReflectionTestUtils.setField(controller, "submissionRepo", submissions);
        wireGrading(controller, submissions, ai, assignments);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
        Person alice = student(1L, "alice"); Person bob = student(2L, "bob");
        when(people.findByUid("alice")).thenReturn(alice); when(people.findById(2L)).thenReturn(Optional.of(bob));
        when(assignments.findById(1L)).thenReturn(Optional.of(assignment(1L, "test")));
        var request = new AssignmentSubmissionAPIController.SubmissionRequestDto(); request.submitterId = 2L;
        assertEquals(403, controller.submitAssignment(1L, request, login("alice")).getStatusCode().value());
        assertEquals(401, controller.submitAssignment(1L, request, null).getStatusCode().value());
        verifyNoInteractions(submissions, ai);
    }
    @Test void submissionIsSavedAndFlaggedForReviewWhenAiFails() throws Exception {
        var submissions = mock(AssignmentSubmissionJPA.class); var ai = mock(AssignmentAiGradingService.class);
        var gradingService = new SubmissionGradingService(submissions, mock(AssignmentJpaRepository.class), mock(SubmissionGradeEventJpaRepository.class),
            new AssignmentAuthorizationService());
        var submission = submission(1L, assignment(1L, "test"));
        when(ai.grade(submission)).thenReturn(AssignmentAiGradingService.GradeResult.failed("No provider available"));
        openAndLockable(submissions, 1L);
        when(submissions.flagForAiRegrade(1L, 1)).thenReturn(1);
        var result = new SubmissionAiGrader(ai, gradingService, mock(GradeReviewService.class)).autoGrade(submission);
        assertEquals(GradingOutcome.AI_FAILED, result.outcome());
        assertNull(submission.getGrade()); verify(submissions).flagForAiRegrade(1L, 1);
        verify(submissions, never()).save(any());
    }
    @Test void authenticatedSubmissionReturnsCreatedEvenWhenAllAiProvidersFail() throws Exception {
        var controller = new AssignmentSubmissionAPIController();
        var people = mock(PersonJpaRepository.class); var assignments = mock(AssignmentJpaRepository.class);
        var submissions = mock(AssignmentSubmissionJPA.class); var ai = mock(AssignmentAiGradingService.class);
        ReflectionTestUtils.setField(controller, "personRepo", people);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignments);
        ReflectionTestUtils.setField(controller, "submissionRepo", submissions);
        wireGrading(controller, submissions, ai, assignments);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
        Person alice = student(1L, "alice"); var assignment = assignment(1L, "test"); assignment.setAssignmentType("code");
        when(people.findByUid("alice")).thenReturn(alice); when(people.findById(1L)).thenReturn(Optional.of(alice));
        when(assignments.findById(1L)).thenReturn(Optional.of(assignment));
        when(submissions.save(any())).thenAnswer(invocation -> {
            AssignmentSubmission saved = invocation.getArgument(0); saved.setId(99L); return saved;
        });
        when(ai.grade(any())).thenReturn(AssignmentAiGradingService.GradeResult.failed("No provider available"));
        openAndLockable(submissions, 99L);
        when(submissions.flagForAiRegrade(99L, 1)).thenReturn(1);
        var request = new AssignmentSubmissionAPIController.SubmissionRequestDto(); request.submitterId = 1L;
        request.content = Map.of("type", "code", "url", "https://gist.github.com/alice/123abcdef");
        var response = controller.submitAssignment(1L, request, login("alice"));
        assertEquals(201, response.getStatusCode().value());
        var dto = (AssignmentSubmissionReturnDto) response.getBody(); assertEquals(99L, dto.id); assertNull(dto.grade);
        verify(submissions, times(1)).save(any()); verify(submissions).flagForAiRegrade(99L, 1);
    }
    @Test void teacherRegradeKeepsExistingAuthorizationAndPassesSubmissionNotTeacherIdentity() throws Exception {
        var controller = new AssignmentSubmissionAPIController();
        var people = mock(PersonJpaRepository.class); var submissions = mock(AssignmentSubmissionJPA.class);
        var ai = mock(AssignmentAiGradingService.class); var assignments = mock(AssignmentJpaRepository.class);
        ReflectionTestUtils.setField(controller, "personRepo", people);
        ReflectionTestUtils.setField(controller, "submissionRepo", submissions);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignments);
        wireGrading(controller, submissions, ai, assignments);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
        when(people.findByUid("teacher")).thenReturn(teacher(2L, "teacher"));
        when(people.findByUid("alice")).thenReturn(student(1L, "alice"));
        var submission = submission(1L, assignment(1L, "test")); submission.setSubmitter(student(1L, "alice"));
        when(submissions.findById(1L)).thenReturn(Optional.of(submission));
        when(assignments.findById(1L)).thenReturn(Optional.of(submission.getAssignment()));
        when(ai.grade(submission)).thenReturn(AssignmentAiGradingService.GradeResult.failed("Unavailable"));
        openAndLockable(submissions, 1L);
        assertEquals(403, controller.aiGradeSubmission(1L, login("alice")).getStatusCode().value());
        assertEquals(502, controller.aiGradeSubmission(1L, login("teacher")).getStatusCode().value());
        verify(ai, times(1)).grade(same(submission));
    }
    /** Stubs the submission as existing and open. */
    private static void openAndLockable(AssignmentSubmissionJPA submissions, Long id) {
        when(submissions.lockForGrading(id)).thenReturn(1);
        when(submissions.findGradingState(id)).thenReturn(Optional.of(new SubmissionGradingState(
            id, 1, null, null, null, null, null, null, false, null, null, false, null)));
    }
    private static void wireGrading(AssignmentSubmissionAPIController controller, AssignmentSubmissionJPA submissions,
            AssignmentAiGradingService ai, AssignmentJpaRepository assignments) {
        when(assignments.lockIfOpen(any())).thenReturn(1); // every assignment here is open
        var gradingService = new SubmissionGradingService(submissions, assignments, mock(SubmissionGradeEventJpaRepository.class),
            new AssignmentAuthorizationService());
        ReflectionTestUtils.setField(controller, "gradingService", gradingService);
        ReflectionTestUtils.setField(controller, "aiGrader", new SubmissionAiGrader(ai, gradingService, mock(GradeReviewService.class)));
        ReflectionTestUtils.setField(controller, "historyService", new AssignmentSubmissionHistoryService(submissions, gradingService,
                org.mockito.Mockito.mock(com.open.spring.mvc.lessonfeedback.LessonFeedbackService.class)));
    }
    private UserDetails login(String uid) { return User.withUsername(uid).password("unused").authorities("ROLE_USER").build(); }
}
