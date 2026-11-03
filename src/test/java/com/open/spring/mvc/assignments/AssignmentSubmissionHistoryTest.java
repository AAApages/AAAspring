package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.lessonfeedback.LessonFeedbackService;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.synergy.GradebookService;

/**
 * A resubmit becomes the next revision of the existing submission instead of a second row; rows
 * from before that (a resubmit used to add a row) are left alone and read as earlier versions.
 */
class AssignmentSubmissionHistoryTest {

    private AssignmentSubmissionJPA submissionRepo;
    private SubmissionGradingService gradingService;
    private LessonFeedbackService lessonFeedback;
    private AssignmentSubmissionHistoryService service;
    private Person alice;
    private Assignment assignment;

    @BeforeEach
    void setUp() {
        submissionRepo = mock(AssignmentSubmissionJPA.class);
        gradingService = mock(SubmissionGradingService.class);
        lessonFeedback = mock(LessonFeedbackService.class); // false by default: no feedback is missing
        service = new AssignmentSubmissionHistoryService(submissionRepo, gradingService, lessonFeedback);
        alice = student(1L, "alice");
        assignment = assignment(10L, "Loops");
        when(gradingService.submit(any(), any())).thenReturn(GradingResult.of(GradingOutcome.REVISION_CREATED, null));
        when(gradingService.revise(any(), any(), any())).thenReturn(GradingResult.of(GradingOutcome.REVISION_CREATED, null));
    }

    private AssignmentSubmission draft(String url) {
        return new AssignmentSubmission(assignment, alice, Map.of("type", "link", "url", url), "new comment", false);
    }

    private AssignmentSubmission stored(Long id, String url, Double grade) {
        AssignmentSubmission submission = submission(id, assignment);
        submission.setSubmitter(alice);
        submission.setContent(Map.of("type", "link", "url", url));
        submission.setGrade(grade);
        return submission;
    }

    private static SubmissionGradeEventEntity event(int revision, SubmissionGradeEventEntity.Type type, String url, Double grade,
            int minute) {
        SubmissionGradeEventEntity event = SubmissionGradeEventEntity.of(7L, revision, type, LocalDateTime.of(2026, 10, 27, 9, minute));
        event.setContent(url == null ? null : Map.of("type", "link", "url", url));
        event.setGrade(grade);
        event.setEffective(grade != null);
        return event;
    }

    @Nested
    class Submit {
        @Test
        void firstSubmissionIsANewSubmission() {
            AssignmentSubmission draft = draft("https://a");

            AssignmentSubmissionHistoryService.SubmitResult result = service.submit(draft, alice);

            assertFalse(result.replaced());
            assertSame(draft, result.submission());
            verify(gradingService).submit(draft, alice);
            verify(gradingService, never()).revise(any(), any(), any());
        }

        @Test
        void resubmitRevisesTheExistingSubmission() {
            AssignmentSubmission current = stored(5L, "https://old", 0.9);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current));
            AssignmentSubmission draft = draft("https://new");

            AssignmentSubmissionHistoryService.SubmitResult result = service.submit(draft, alice);

            assertTrue(result.replaced());
            assertSame(current, result.submission());
            assertEquals("new comment", current.getComment());
            verify(gradingService).revise(same(current), eq(draft.getContent()), same(alice));
            verify(gradingService, never()).submit(any(), any());
        }

        @Test
        void resubmitOverOldDuplicatesRevisesTheNewestAndLeavesOlderRowsAlone() {
            AssignmentSubmission oldest = stored(3L, "https://first", 0.8);
            AssignmentSubmission newest = stored(7L, "https://second", 0.5);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(oldest, newest));

            service.submit(draft("https://third"), alice);

            verify(gradingService).revise(same(newest), any(), any());
            assertEquals("https://first", oldest.getContent().get("url"));
        }

        @Test
        void homeworkWaitsForMissingLessonFeedbackAndSavesNothing() {
            when(lessonFeedback.isFeedbackMissing(alice, assignment)).thenReturn(true);

            AssignmentSubmissionHistoryService.SubmitResult result = service.submit(draft("https://a"), alice);

            assertEquals(GradingOutcome.LESSON_FEEDBACK_REQUIRED, result.result().outcome());
            verify(gradingService, never()).submit(any(), any());
            verify(gradingService, never()).revise(any(), any(), any());
        }
    }

    /** The first revision of a submission from before the grading history records what it replaces. */
    @Nested
    class PreHistoryRevision {
        private SubmissionGradeEventJpaRepository eventRepo;
        private SubmissionGradingService realGrading;
        private AssignmentSubmission legacy;

        @BeforeEach
        void setUpGrading() {
            eventRepo = mock(SubmissionGradeEventJpaRepository.class);
            realGrading = new SubmissionGradingService(submissionRepo, mock(AssignmentJpaRepository.class), eventRepo,
                    new AssignmentAuthorizationService());
            legacy = stored(5L, "https://legacy", 0.6);
            when(submissionRepo.lockForGrading(5L)).thenReturn(1);
            when(submissionRepo.findGradingState(5L)).thenReturn(Optional.of(new SubmissionGradingState(
                    5L, 1, 0.6, "legacy feedback", null, null, null, null, false, null, null, false, null)));
            when(submissionRepo.startRevision(eq(5L), eq(1), any())).thenReturn(1);
        }

        @Test
        void itsContentAndGradeAreSavedBeforeTheRevisionReplacesThem() {
            when(eventRepo.findBySubmissionIdOrderByIdAsc(5L)).thenReturn(List.of());

            realGrading.revise(legacy, Map.of("type", "link", "url", "https://new"), alice);

            ArgumentCaptor<SubmissionGradeEventEntity> saved = ArgumentCaptor.forClass(SubmissionGradeEventEntity.class);
            verify(eventRepo, times(3)).save(saved.capture());
            SubmissionGradeEventEntity submitted = saved.getAllValues().get(0);
            assertEquals(SubmissionGradeEventEntity.Type.SUBMITTED, submitted.getType());
            assertEquals(1, submitted.getRevision());
            assertEquals("https://legacy", submitted.getContent().get("url"));
            assertNull(submitted.getActorId());
            SubmissionGradeEventEntity graded = saved.getAllValues().get(1);
            assertEquals(SubmissionGradeEventEntity.Type.AI_GRADED, graded.getType());
            assertEquals(0.6, graded.getGrade());
            assertTrue(graded.getEffective());
            assertEquals(SubmissionGradeEventEntity.Type.REVISED, saved.getAllValues().get(2).getType());
        }

        @Test
        void aSubmissionWithHistoryIsNotRecordedAgain() {
            when(eventRepo.findBySubmissionIdOrderByIdAsc(5L))
                    .thenReturn(List.of(event(1, SubmissionGradeEventEntity.Type.SUBMITTED, "https://legacy", null, 0)));

            realGrading.revise(legacy, Map.of("type", "link", "url", "https://new"), alice);

            ArgumentCaptor<SubmissionGradeEventEntity> saved = ArgumentCaptor.forClass(SubmissionGradeEventEntity.class);
            verify(eventRepo).save(saved.capture());
            assertEquals(SubmissionGradeEventEntity.Type.REVISED, saved.getValue().getType());
        }
    }

    @Nested
    class Versions {
        @Test
        void latestOnlyKeepsTheNewestRowPerAssignmentAndSubmitter() {
            AssignmentSubmission older = stored(1L, "https://1", 0.5);
            AssignmentSubmission newer = stored(2L, "https://2", null);
            AssignmentSubmission otherAssignment = submission(3L, assignment(11L, "Arrays"));
            otherAssignment.setSubmitter(alice);

            assertEquals(List.of(newer, otherAssignment),
                    AssignmentSubmissionHistoryService.latestOnly(List.of(older, newer, otherAssignment)));
        }

        @Test
        void historyListsCurrentThenEarlierRevisionsThenOldDuplicates() {
            AssignmentSubmission legacy = stored(3L, "https://legacy", 0.4);
            AssignmentSubmission current = stored(7L, "https://now", null);
            current.setRevision(3);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current, legacy));
            when(gradingService.history(7L)).thenReturn(List.of(
                    event(1, SubmissionGradeEventEntity.Type.SUBMITTED, "https://first", null, 0),
                    event(1, SubmissionGradeEventEntity.Type.AI_GRADED, null, 0.6, 1),
                    event(2, SubmissionGradeEventEntity.Type.REVISED, "https://second", null, 2),
                    event(2, SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_SET, null, 0.8, 3),
                    event(3, SubmissionGradeEventEntity.Type.REVISED, "https://now", null, 4)));

            assertSame(current, service.currentOf(legacy));
            List<AssignmentSubmissionHistoryService.Version> versions = service.historyOf(current).versions();

            assertEquals(4, versions.size());
            assertTrue(versions.get(0).current());
            assertEquals("https://now", versions.get(0).content().get("url"));
            assertEquals(LocalDateTime.of(2026, 10, 27, 9, 4), versions.get(0).submittedAt());
            assertNull(versions.get(2).submittedAt()); // revision 1 was recorded later, with no actor
            assertEquals(2, versions.get(1).revision());
            assertEquals("https://second", versions.get(1).content().get("url"));
            assertEquals(0.8, versions.get(1).grade());
            assertEquals(LocalDateTime.of(2026, 10, 27, 9, 4), versions.get(1).replacedAt());
            assertEquals(0.6, versions.get(2).grade());
            assertEquals(3L, versions.get(3).submissionId());
            assertEquals(0.4, versions.get(3).grade());
        }

        @Test
        void aKeptManualGradeLeavesEachRevisionWithTheGradeItHad() {
            AssignmentSubmission current = stored(7L, "https://v2", 0.9);
            current.setRevision(2);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current));
            when(gradingService.history(7L)).thenReturn(List.of(
                    event(1, SubmissionGradeEventEntity.Type.SUBMITTED, "https://v1", null, 0),
                    event(1, SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_SET, null, 0.5, 1),
                    event(2, SubmissionGradeEventEntity.Type.REVISED, "https://v2", null, 2),
                    event(2, SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_KEPT_BY_REVISION, null, 0.5, 2),
                    event(2, SubmissionGradeEventEntity.Type.MANUAL_OVERRIDE_SET, null, 0.9, 3)));

            List<AssignmentSubmissionHistoryService.Version> versions = service.historyOf(current).versions();

            assertEquals(2, versions.size());
            assertEquals(0.9, versions.get(0).grade(), "the petition's new grade is on revision 2");
            assertEquals(1, versions.get(1).revision());
            assertEquals(0.5, versions.get(1).grade());
            assertEquals(LocalDateTime.of(2026, 10, 27, 9, 2), versions.get(1).replacedAt());
        }

        @Test
        void gradebookCardShowsOnlyTheCurrentRowAndCountsItsEarlierVersions() {
            AssignmentSubmission older = stored(1L, "https://1", 0.5);
            AssignmentSubmission newer = stored(2L, "https://2", null);
            newer.setRevision(3);
            AssignmentAiRegradeService queue = mock(AssignmentAiRegradeService.class);
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(older, newer));
            when(queue.queueSnapshot()).thenReturn(new AssignmentAiRegradeService.QueueSnapshot(false, 0, 15, List.of()));
            GradebookService gradebook = new GradebookService(mock(GroupsJpaRepository.class), submissionRepo, queue,
                    new CourseGroupProperties(), mock(JdbcTemplate.class), mock(PersonJpaRepository.class));

            List<GradebookService.SubmissionEntry> entries = gradebook.card(alice).submissions();

            assertEquals(1, entries.size());
            assertEquals(2L, entries.get(0).submissionId());
            assertEquals(3, entries.get(0).earlierVersions()); // 2 earlier revisions + 1 old duplicate row
        }
    }

    @Nested
    class Api {
        private AssignmentSubmissionAPIController controller;
        private PersonJpaRepository people;
        private GroupsJpaRepository groups;

        @BeforeEach
        void setUpController() {
            controller = new AssignmentSubmissionAPIController();
            people = mock(PersonJpaRepository.class);
            groups = mock(GroupsJpaRepository.class);
            AssignmentJpaRepository assignments = mock(AssignmentJpaRepository.class);
            ReflectionTestUtils.setField(controller, "personRepo", people);
            ReflectionTestUtils.setField(controller, "assignmentRepo", assignments);
            ReflectionTestUtils.setField(controller, "groupRepo", groups);
            ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
            ReflectionTestUtils.setField(controller, "gradingService", gradingService);
            ReflectionTestUtils.setField(controller, "aiGrader", mock(SubmissionAiGrader.class));
            ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
            ReflectionTestUtils.setField(controller, "historyService", service);
            when(people.findByUid("alice")).thenReturn(alice);
            when(people.findById(1L)).thenReturn(Optional.of(alice));
            when(people.findByUid("bob")).thenReturn(student(2L, "bob"));
            assignment.setAssignmentType("link");
            when(assignments.findById(10L)).thenReturn(Optional.of(assignment));
        }

        private UserDetails login(String uid) {
            return User.withUsername(uid).password("unused").authorities("ROLE_USER").build();
        }

        private AssignmentSubmissionAPIController.SubmissionRequestDto request(String url) {
            var request = new AssignmentSubmissionAPIController.SubmissionRequestDto();
            request.submitterId = 1L;
            request.content = Map.of("type", "link", "url", url);
            return request;
        }

        @Test
        void resubmitReturnsOkWithTheSameSubmissionId() {
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(stored(5L, "https://old", 0.9)));

            var response = controller.submitAssignment(10L, request("https://new"), login("alice"));

            assertEquals(200, response.getStatusCode().value());
            assertEquals(5L, ((AssignmentSubmissionReturnDto) response.getBody()).id);
        }

        @Test
        void firstSubmitStillReturnsCreated() {
            assertEquals(201, controller.submitAssignment(10L, request("https://new"), login("alice")).getStatusCode().value());
        }

        @Test
        void resubmitToAClosedAssignmentIsRefused() {
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(stored(5L, "https://old", 0.9)));
            when(gradingService.revise(any(), any(), any())).thenReturn(GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, null));

            var response = controller.submitAssignment(10L, request("https://new"), login("alice"));

            assertEquals(GradingResponse.from(GradingResult.of(GradingOutcome.ASSIGNMENT_CLOSED, null)).getStatusCode(),
                    response.getStatusCode());
        }

        @Test
        void submitWithoutLessonFeedbackAnswersConflictWithTheTypedOutcome() {
            when(lessonFeedback.isFeedbackMissing(alice, assignment)).thenReturn(true);

            var response = controller.submitAssignment(10L, request("https://new"), login("alice"));

            assertEquals(409, response.getStatusCode().value());
            assertEquals(GradingOutcome.LESSON_FEEDBACK_REQUIRED, ((GradingResponse) response.getBody()).outcome());
        }

        @Test
        void studentCanSubmitForTheirGroupButNotAnotherGroup() {
            Groups team = new Groups();
            ReflectionTestUtils.setField(team, "id", 50L);
            when(groups.findById(50L)).thenReturn(Optional.of(team));
            when(groups.findGroupsByPersonId(1L)).thenReturn(List.of(team));
            var request = request("https://group");
            request.submitterId = 50L;
            request.isGroupSubmission = true;

            assertEquals(201, controller.submitAssignment(10L, request, login("alice")).getStatusCode().value());
            assertEquals(403, controller.submitAssignment(10L, request, login("bob")).getStatusCode().value());
        }

        @Test
        void historyIsVisibleToTheSubmitterButNotAnotherStudent() {
            AssignmentSubmission current = stored(5L, "https://now", null);
            when(submissionRepo.findById(5L)).thenReturn(Optional.of(current));
            when(submissionRepo.findBySubmitterId(1L)).thenReturn(List.of(current));
            when(gradingService.history(5L)).thenReturn(List.of());

            assertEquals(200, controller.getSubmissionHistory(5L, login("alice")).getStatusCode().value());
            assertEquals(403, controller.getSubmissionHistory(5L, login("bob")).getStatusCode().value());
            assertEquals(404, controller.getSubmissionHistory(6L, login("alice")).getStatusCode().value());
            assertNull(service.historyOf(current).versions().get(0).submittedAt());
        }
    }
}
