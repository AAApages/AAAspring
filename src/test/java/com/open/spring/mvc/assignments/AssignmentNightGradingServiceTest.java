package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;

import com.open.spring.mvc.aiconnections.AiHouseModelClient;
import com.open.spring.mvc.aiconnections.AiHouseSettingsRepository;
import com.open.spring.mvc.aiconnections.AiKeyCipher;
import com.open.spring.mvc.aiconnections.AiProviderFailure;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

class AssignmentNightGradingServiceTest {

    private final AssignmentAiRegradeService queue = mock(AssignmentAiRegradeService.class);
    private final AssignmentJpaRepository assignmentRepo = mock(AssignmentJpaRepository.class);
    private final SubmissionGradingService gradingService = mock(SubmissionGradingService.class);
    private final AiHouseSettingsRepository settings = mock(AiHouseSettingsRepository.class);
    private final AiHouseModelClient house = mock(AiHouseModelClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AssignmentNightGradingService night =
            new AssignmentNightGradingService(queue, assignmentRepo, gradingService, settings, house, jdbc);

    // The "database": rows the three work queries return. Tests remove rows as work completes.
    private final List<Map<String, Object>> queued = new ArrayList<>();
    private final List<Map<String, Object>> ungraded = new ArrayList<>();
    private final List<Map<String, Object>> rubricless = new ArrayList<>();
    private final Assignment homework = assignment(5L, "homework");
    private final Assignment lesson = assignment(10L, "lesson");

    @BeforeEach
    void setUp() {
        night.setExecutor(Runnable::run);
        when(jdbc.queryForList(contains("needs_ai_regrade = 1"))).thenAnswer(call -> new ArrayList<>(queued));
        when(jdbc.queryForList(contains("ai_grade IS NULL"))).thenAnswer(call -> new ArrayList<>(ungraded));
        when(jdbc.queryForList(contains("FROM assignment WHERE"))).thenAnswer(call -> new ArrayList<>(rubricless));
        when(settings.isNightWindowOpen()).thenReturn(true);
        when(settings.current()).thenReturn(houseSettings());
        when(house.isConfigured()).thenReturn(true);
        when(house.isAvailable()).thenReturn(true);
        when(queue.tryAcquireRun()).thenReturn(true);
        when(assignmentRepo.findById(5L)).thenReturn(Optional.of(homework));
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(lesson));
        when(queue.ensureAiRubric(any(), eq(true))).thenReturn(true);
    }

    private static AiHouseSettingsRepository.HouseSettings houseSettings() {
        return new AiHouseSettingsRepository.HouseSettings(true, "22:00", "06:00", "America/Los_Angeles",
                AiHouseSettingsRepository.Preset.CUSTOM, "http://gpu.test/v1", "qwen", "gemma", 900, false,
                Map.of(), null, null);
    }

    private static Map<String, Object> submissionRow(long id, long assignmentId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("assignment_id", assignmentId);
        return row;
    }

    private static Map<String, Object> assignmentRow(long id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("ai_rubric", Assignment.DEFAULT_AI_RUBRIC);
        row.put("points", 1.0);
        return row;
    }

    /** gradeOnce grades the submission and, like the real database, it leaves the work queries. */
    private void gradesSucceed() {
        when(queue.gradeOnce(anyLong(), any(), anyBoolean(), eq(true))).thenAnswer(call -> {
            long id = call.getArgument(0);
            queued.removeIf(row -> ((Long) row.get("id")) == id);
            ungraded.removeIf(row -> ((Long) row.get("id")) == id);
            return GradingOutcome.AI_GRADE_APPLIED;
        });
    }

    @Test
    void withoutAHouseModelTheTickGradesNothingAndCallsNoModel() {
        queued.add(submissionRow(1, 5));
        when(house.isAvailable()).thenReturn(false);

        night.tick();

        verify(queue, never()).tryAcquireRun();
        verify(queue, never()).gradeOnce(anyLong(), any(), anyBoolean(), anyBoolean());
        verify(queue, never()).ensureAiRubric(any(), anyBoolean());
        assertEquals(1, night.waiting().queued());
    }

    @Test
    void outsideTheWindowTheTickDoesNothing() {
        queued.add(submissionRow(1, 5));
        when(settings.isNightWindowOpen()).thenReturn(false);

        night.tick();

        verifyNoInteractions(house);
        verify(queue, never()).tryAcquireRun();
    }

    @Test
    void gradesQueuedThenUngradedThenGeneratesMissingRubrics() {
        queued.add(submissionRow(1, 5));
        ungraded.add(submissionRow(2, 5));
        rubricless.add(assignmentRow(10));
        gradesSucceed();
        when(queue.ensureAiRubric(lesson, true)).thenAnswer(call -> rubricless.removeIf(row -> true));

        night.tick();

        var order = inOrder(queue);
        order.verify(queue).gradeOnce(1L, homework, true, true);
        order.verify(queue).gradeOnce(2L, homework, false, true);
        order.verify(queue).ensureAiRubric(lesson, true);
        order.verify(queue).releaseRun();
        var run = night.status().lastRun();
        assertEquals(2, run.graded());
        assertEquals(1, run.rubrics());
        assertEquals("no work left", run.endReason());
    }

    @Test
    void eachItemIsTriedOncePerNightAndAFailedUngradedOneIsQueued() {
        ungraded.add(submissionRow(2, 5));
        when(queue.gradeOnce(2L, homework, false, true)).thenReturn(GradingOutcome.AI_FAILED);
        var state = new SubmissionGradingState(2L, 4, null, null, null, null, null, null, false, null, null, false, null);
        when(gradingService.currentState(2L)).thenReturn(Optional.of(state));

        night.tick();
        night.tick(); // Still the same night: nothing new to try, so no run starts.

        verify(queue).gradeOnce(2L, homework, false, true);
        verify(queue, org.mockito.Mockito.times(1)).tryAcquireRun();
        verify(gradingService).flagForAiRegrade(2L, 4);
        assertEquals(1, night.status().lastRun().failed());

        when(settings.isNightWindowOpen()).thenReturn(false);
        night.tick(); // Morning clears tonight's list.
        when(settings.isNightWindowOpen()).thenReturn(true);
        night.tick();
        verify(queue, org.mockito.Mockito.times(2)).gradeOnce(2L, homework, false, true);
    }

    @Test
    void theRunStopsWhenTheWindowCloses() {
        queued.add(submissionRow(1, 5));
        queued.add(submissionRow(2, 5));
        gradesSucceed();
        // tick, then the loop's check before item 1, then before item 2.
        when(settings.isNightWindowOpen()).thenReturn(true, true, false);

        night.tick();

        verify(queue).gradeOnce(1L, homework, true, true);
        verify(queue, never()).gradeOnce(eq(2L), any(), anyBoolean(), anyBoolean());
        assertEquals("window closed", night.status().lastRun().endReason());
        verify(queue).releaseRun();
    }

    @Test
    void aServerThatGoesDownMidRunLeavesTheItemForLater() {
        queued.add(submissionRow(1, 5));
        when(queue.gradeOnce(1L, homework, true, true)).thenReturn(GradingOutcome.AI_FAILED);
        when(house.isAvailable()).thenReturn(true, true, false); // tick, loop check, after the failure

        night.tick();

        assertEquals("house model unavailable", night.status().lastRun().endReason());
        when(house.isAvailable()).thenReturn(true);
        assertNotNull(night.nextItem()); // Not marked as tried, so it is retried once the server is back.
    }

    @Test
    void aRubricThatCantBeMadeSkipsThatAssignmentsSubmissionsTonight() {
        queued.add(submissionRow(1, 5));
        queued.add(submissionRow(2, 5));
        when(queue.ensureAiRubric(homework, true)).thenReturn(false);

        night.tick();

        verify(queue, never()).gradeOnce(anyLong(), any(), anyBoolean(), anyBoolean());
        assertNull(night.nextItem());
        assertEquals(1, night.status().lastRun().failed());
    }

    @Test
    void submissionsATeacherIsGradingNowAreSkipped() {
        queued.add(submissionRow(1, 5));
        when(queue.isGradingNow(1L)).thenReturn(true);

        assertNull(night.nextItem());
    }

    @Test
    void closedAssignmentsAreSkipped() {
        queued.add(submissionRow(1, 5));
        homework.setClosedAt(java.time.LocalDateTime.now());

        night.tick();

        verify(queue, never()).gradeOnce(anyLong(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void startNowWithoutAHouseModelOnlyReportsTheCounts() {
        queued.add(submissionRow(1, 5));
        when(house.isConfigured()).thenReturn(false);

        var result = night.startNow();

        assertEquals("no_house_model", result.status());
        assertEquals(1, result.waiting().queued());
        verify(queue, never()).tryAcquireRun();
    }

    @Test
    void startNowIgnoresTheWindowAndReportsBusy() {
        queued.add(submissionRow(1, 5));
        gradesSucceed();
        when(settings.isNightWindowOpen()).thenReturn(false);

        assertEquals("started", night.startNow().status());
        verify(queue).gradeOnce(1L, homework, true, true);
        assertTrue(night.status().lastRun().manual());

        when(queue.tryAcquireRun()).thenReturn(false);
        assertEquals("busy", night.startNow().status());
    }

    /** The daytime paths stay off the paid models during the night window. */
    @Nested
    class DaytimePathsAtNight {
        @Test
        void autoGradeOnlyQueuesTheRevision() throws Exception {
            var aiGradingService = mock(AssignmentAiGradingService.class);
            var grader = new SubmissionAiGrader(aiGradingService, gradingService, mock(GradeReviewService.class), settings);
            var work = submission(100L, assignment(10L, "pilot"));
            work.setRevision(3);
            when(gradingService.flagForAiRegrade(100L, 3)).thenReturn(true);

            var result = grader.autoGrade(work);

            assertEquals(GradingOutcome.AI_FAILED, result.outcome());
            assertEquals("Queued for night grading.", result.detail());
            verify(gradingService).flagForAiRegrade(100L, 3);
            verifyNoInteractions(aiGradingService);
        }

        @Test
        void autoGradeGradesNormallyInTheDay() throws Exception {
            when(settings.isNightWindowOpen()).thenReturn(false);
            var aiGradingService = mock(AssignmentAiGradingService.class);
            var grader = new SubmissionAiGrader(aiGradingService, gradingService, mock(GradeReviewService.class), settings);
            var work = submission(100L, assignment(10L, "pilot"));
            when(gradingService.currentState(100L)).thenReturn(Optional.of(new SubmissionGradingState(
                    100L, 1, null, null, null, null, null, null, false, null, null, false, null)));
            when(aiGradingService.grade(work)).thenReturn(AssignmentAiGradingService.GradeResult.failed("x"));

            grader.autoGrade(work);

            verify(aiGradingService).grade(work);
        }

        @Test
        void theCloudQueueAndRubricRequestsWaitForMorning() {
            var cloud = new AssignmentAiRegradeService(mock(AssignmentSubmissionJPA.class), assignmentRepo,
                    mock(SubmissionAiGrader.class), gradingService, mock(AssignmentRubricService.class), jdbc,
                    settings, house);

            cloud.runOnce();
            assertFalse(cloud.runQueueNow());
            cloud.requestRubric(10L, "lesson", "", "");

            verify(jdbc, never()).queryForList(contains("needs_ai_regrade = 1"));
            verify(assignmentRepo, never()).findById(anyLong());
        }
    }

    /** Who may see, start and change night grading; the key never leaves the server. */
    @Nested
    class Api {
        private final PersonJpaRepository people = mock(PersonJpaRepository.class);
        private final AssignmentAuthorizationService authorization = mock(AssignmentAuthorizationService.class);
        private final NightGradingApiRestController api = new NightGradingApiRestController(night, settings, house,
                new AiKeyCipher(""), authorization, people);

        private UserDetails signIn(String uid, boolean teacher, boolean admin) {
            Person person = mock(Person.class);
            when(person.getUid()).thenReturn(uid);
            when(person.hasRole(AssignmentAuthorizationService.ROLE_ADMIN)).thenReturn(admin);
            when(authorization.isTeacherOrAdmin(person)).thenReturn(teacher || admin);
            when(people.findByUid(uid)).thenReturn(person);
            UserDetails details = mock(UserDetails.class);
            when(details.getUsername()).thenReturn(uid);
            return details;
        }

        private AiHouseSettingsRepository.Changes changes() {
            return new AiHouseSettingsRepository.Changes(true, "22:00", "06:00", "UTC", "CUSTOM",
                    "http://gpu.test/v1", "qwen", "", 900, "secret-key", false);
        }

        @Test
        void studentsSeeStatusButCannotStartOrReadSettings() {
            UserDetails student = signIn("hop", false, false);
            assertEquals(HttpStatus.OK, api.status(student).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, api.runNow(student).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, api.getSettings(student).getStatusCode());
        }

        @Test
        void teachersReadSettingsButOnlyAdminsChangeOrTestThem() {
            UserDetails teacher = signIn("niko", true, false);
            assertEquals(HttpStatus.OK, api.getSettings(teacher).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, api.saveSettings(changes(), teacher).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, api.resetSettings(teacher).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, api.testConnection(
                    new NightGradingApiRestController.ConnectionTest("http://x/v1", null), teacher).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, api.testPrompt(teacher).getStatusCode());
            verify(settings, never()).save(any(), any());
            verify(house, never()).listModels(any(), any());
        }

        @Test
        void adminsSaveAndTheResponseNeverHoldsTheKey() throws Exception {
            UserDetails admin = signIn("toby", true, true);
            var response = api.saveSettings(changes(), admin);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(settings).save(any(), eq("toby"));
            verify(house).resetBreaker();
            String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(response.getBody());
            assertFalse(json.contains("secret-key"));
            assertTrue(json.contains("\"canEdit\":true"));
        }

        @Test
        void aFailedConnectionTestIsABadGatewayWithOurOwnMessage() {
            UserDetails admin = signIn("toby", true, true);
            when(house.listModels("http://gpu.test/v1", null))
                    .thenThrow(new AiProviderFailure(true, "The house model server could not be reached."));
            var response = api.testConnection(new NightGradingApiRestController.ConnectionTest("http://gpu.test/v1", null), admin);
            assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        }

        @Test
        void runNowStatuses() {
            UserDetails teacher = signIn("niko", true, false);
            when(house.isConfigured()).thenReturn(false);
            assertEquals(HttpStatus.OK, api.runNow(teacher).getStatusCode());
            when(house.isConfigured()).thenReturn(true);
            when(queue.tryAcquireRun()).thenReturn(false);
            assertEquals(HttpStatus.CONFLICT, api.runNow(teacher).getStatusCode());
            when(queue.tryAcquireRun()).thenReturn(true);
            assertEquals(HttpStatus.ACCEPTED, api.runNow(teacher).getStatusCode());
        }

        @Test
        void signedOutIsUnauthorized() {
            assertEquals(HttpStatus.UNAUTHORIZED, api.status(null).getStatusCode());
        }
    }
}
