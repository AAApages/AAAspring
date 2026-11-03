package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.syncBot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.assignments.AssignmentsApiController.AssignmentDto;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import java.util.HashMap;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Covers the ownership half of POST /api/assignments/auto-create and GET /api/assignments/managed.
 */
class AssignmentsApiControllerCreatorTest {

    /**
     * What a caller actually sends. Deliberately not canonical - the browser posts Jekyll's
     * page.url, which is leading- and trailing-slashed - so these tests exercise the
     * normalization that makes both callers land on one assignment.
     */
    private static final String CONTENT_URL = "/csa/sample-assignment/";

    /** The form that gets stored in, and looked up from, the content_url column. */
    private static final String CANONICAL_CONTENT_URL = "csa/sample-assignment";

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private GroupsJpaRepository groupsRepository;

    @Mock
    private AssignmentAiRegradeService aiRegradeService;

    private AssignmentsApiController controller;
    private AssignmentCreatorSyncService creatorSyncService;
    private AssignmentCourseSyncService courseSyncService;

    private Person bot;
    private Person firstCreator;
    private Person secondCreator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        creatorSyncService = new AssignmentCreatorSyncService();
        ReflectionTestUtils.setField(creatorSyncService, "personRepo", personRepo);
        CourseGroupProperties courseProperties = new CourseGroupProperties();
        courseProperties.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
        courseSyncService = new AssignmentCourseSyncService(groupsRepository, courseProperties);

        controller = new AssignmentsApiController();
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
        ReflectionTestUtils.setField(controller, "assignmentCreatorSyncService", creatorSyncService);
        ReflectionTestUtils.setField(controller, "assignmentCourseSyncService", courseSyncService);
        ReflectionTestUtils.setField(controller, "aiRegradeService", aiRegradeService);
        ReflectionTestUtils.setField(controller, "groupRepo", groupsRepository);

        bot = syncBot(100L, "pages-bot");
        firstCreator = student(1L, "AdityaS-2010");
        secondCreator = student(2L, "second-creator");

        when(personRepo.findByUid("pages-bot")).thenReturn(bot);
        when(personRepo.findByUid("AdityaS-2010")).thenReturn(firstCreator);
        when(personRepo.findByUid("second-creator")).thenReturn(secondCreator);
        when(assignmentRepo.save(any(Assignment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserDetails caller(String uid, String... authorities) {
        return User.withUsername(uid).password("ignored").authorities(authorities).build();
    }

    private ResponseEntity<?> autoCreate(UserDetails caller, List<String> creatorUids) {
        return autoCreate(caller, creatorUids, null);
    }

    private ResponseEntity<?> autoCreate(
            UserDetails caller,
            List<String> creatorUids,
            List<String> courseCodes) {
        return autoCreate(caller, creatorUids, courseCodes, null);
    }

    private ResponseEntity<?> autoCreate(
            UserDetails caller,
            List<String> creatorUids,
            List<String> courseCodes,
            Boolean teamTeach) {
        return controller.autoCreateAssignment(
            "Sample Assignment", CONTENT_URL, "", null, null,
            null, null, creatorUids, courseCodes, teamTeach, caller);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void aPageWithNoDescriptionStoresNullSoAiGradingFallsBackToTheName() {
        // GeminiFeedbackService uses `description == null ? name : description` as the
        // rubric it sends to the model; storing "" here would defeat that fallback.
        autoCreate(caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010"));

        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepo).save(saved.capture());
        assertNull(saved.getValue().getDescription());
    }

    @Test
    void aPageDescriptionIsStoredWithoutTheLegacyContentUrlMarker() {
        controller.autoCreateAssignment(
            "Sample Assignment", CONTENT_URL, "  Play the game  ",
            null, null, null, null, null, null, null, caller("admin", "ROLE_ADMIN"));

        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepo).save(saved.capture());
        assertEquals("Play the game", saved.getValue().getDescription());
        assertEquals(CANONICAL_CONTENT_URL, saved.getValue().getContentUrl());
    }

    @Test
    void syncUserCanAssignOneCreator() {
        ResponseEntity<?> response = autoCreate(caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals(List.of("AdityaS-2010"), ((AssignmentDto) response.getBody()).getCreatorUids());
    }

    @Test
    void omittedSubmissionTypePreservesTheExistingAssignmentType() {
        Assignment existing = assignment(10L, "sample", firstCreator);
        existing.setContentUrl(CANONICAL_CONTENT_URL);
        existing.setAssignmentType("github_issue");
        when(assignmentRepo.findFirstByContentUrlOrderByIdAsc(CANONICAL_CONTENT_URL)).thenReturn(existing);

        ResponseEntity<?> response = autoCreate(caller("admin", "ROLE_ADMIN"), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("github_issue", existing.getAssignmentType());
        verify(assignmentRepo, never()).save(any(Assignment.class));
    }

    @Test
    void newAssignmentWithoutSubmissionTypeDefaultsToFile() {
        autoCreate(caller("admin", "ROLE_ADMIN"), null);

        ArgumentCaptor<Assignment> saved = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepo).save(saved.capture());
        assertEquals("file", saved.getValue().getAssignmentType());
    }

    @Test
    void syncUserCanAssignMultipleCreators() {
        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010", "second-creator"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals(List.of("AdityaS-2010", "second-creator"),
            ((AssignmentDto) response.getBody()).getCreatorUids());
    }

    @Test
    void duplicateCreatorUidsAreDeduplicated() {
        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"),
            List.of(" AdityaS-2010 ", "AdityaS-2010", "second-creator"));

        assertEquals(List.of("AdityaS-2010", "second-creator"),
            ((AssignmentDto) response.getBody()).getCreatorUids());
    }

    @Test
    void unknownCreatorUidRejectsTheEntireUpdate() {
        when(personRepo.findByUid("ghost")).thenReturn(null);

        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010", "ghost"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(List.of("ghost"), errorBody(response).get("unknownCreatorUids"));
    }

    @Test
    void aFailedCreatorUpdatePersistsNothing() {
        Assignment existing = assignment(10L, "pilot", firstCreator);
        existing.setContentUrl(CANONICAL_CONTENT_URL);
        when(assignmentRepo.findFirstByContentUrlOrderByIdAsc(CANONICAL_CONTENT_URL)).thenReturn(existing);
        when(personRepo.findByUid("ghost")).thenReturn(null);

        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("second-creator", "ghost"));

        assertEquals(400, response.getStatusCode().value());
        verify(assignmentRepo, never()).save(any(Assignment.class));
        // The previously stored owner list is untouched - no half-applied replacement.
        assertEquals(List.of("AdityaS-2010"), creatorSyncService.creatorUidsOf(existing));
    }

    @Test
    void aStudentCannotSubmitCreatorUids() {
        Person attacker = student(7L, "sneaky-student");
        when(personRepo.findByUid("sneaky-student")).thenReturn(attacker);

        ResponseEntity<?> response = autoCreate(
            caller("sneaky-student", "ROLE_STUDENT"), List.of("sneaky-student"));

        assertEquals(403, response.getStatusCode().value());
        verify(assignmentRepo, never()).save(any(Assignment.class));
    }

    @Test
    void aTeacherCannotSubmitCreatorUidsEither() {
        Person teacher = AssignmentCreatorFixtures.teacher(8L, "mort");
        when(personRepo.findByUid("mort")).thenReturn(teacher);

        ResponseEntity<?> response = autoCreate(caller("mort", "ROLE_TEACHER"), List.of("AdityaS-2010"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void syncUserCanAssignMultipleCourses() {
        Groups csa = new Groups();
        csa.setName("CSA");
        Groups csp = new Groups();
        csp.setName("CSP");
        when(groupsRepository.findByName("CSA")).thenReturn(java.util.Optional.of(csa));
        when(groupsRepository.findByName("CSP")).thenReturn(java.util.Optional.of(csp));

        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"),
            List.of("AdityaS-2010"),
            List.of("csa", "CSP"));

        assertEquals(201, response.getStatusCode().value());
        AssignmentDto body = (AssignmentDto) response.getBody();
        assertEquals(CANONICAL_CONTENT_URL, body.getContentUrl());
        assertEquals(List.of("CSA", "CSP"), body.getCourseCodes());
    }

    @Test
    void unknownCourseRejectsTheEntireUpdate() {
        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"),
            List.of("AdityaS-2010"),
            List.of("UNKNOWN"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(List.of("UNKNOWN"), errorBody(response).get("unknownCourseCodes"));
        verify(assignmentRepo, never()).save(any(Assignment.class));
    }

    @Test
    void studentCannotSubmitCourseCodes() {
        Person attacker = student(7L, "sneaky-student");
        when(personRepo.findByUid("sneaky-student")).thenReturn(attacker);

        ResponseEntity<?> response = autoCreate(
            caller("sneaky-student", "ROLE_STUDENT"), null, List.of("CSA"));

        assertEquals(403, response.getStatusCode().value());
        verify(assignmentRepo, never()).save(any(Assignment.class));
    }

    @Test
    void syncUserMarksANewAssignmentTeamTaught() {
        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010"), null, true);

        assertEquals(201, response.getStatusCode().value());
        assertTrue(((AssignmentDto) response.getBody()).isTeamTeach());
    }

    @Test
    void syncUserClearsTeamTeachWhenThePageDropsIt() {
        Assignment existing = assignment(10L, "pilot", firstCreator);
        existing.setContentUrl(CANONICAL_CONTENT_URL);
        existing.setTeamTeach(true);
        when(assignmentRepo.findFirstByContentUrlOrderByIdAsc(CANONICAL_CONTENT_URL)).thenReturn(existing);

        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010"), null, false);

        assertEquals(200, response.getStatusCode().value());
        assertFalse(existing.getTeamTeach());
        verify(assignmentRepo).save(existing);
    }

    @Test
    void aStudentCannotTurnOffTeamTeach() {
        Person attacker = student(7L, "sneaky-student");
        when(personRepo.findByUid("sneaky-student")).thenReturn(attacker);

        ResponseEntity<?> response = autoCreate(caller("sneaky-student", "ROLE_STUDENT"), null, null, false);

        assertEquals(403, response.getStatusCode().value());
        verify(assignmentRepo, never()).save(any(Assignment.class));
    }

    @Test
    void resynchronizingAnExistingAssignmentUpdatesItsCreators() {
        Assignment existing = assignment(10L, "pilot", firstCreator);
        existing.setContentUrl(CANONICAL_CONTENT_URL);
        when(assignmentRepo.findFirstByContentUrlOrderByIdAsc(CANONICAL_CONTENT_URL)).thenReturn(existing);

        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("second-creator"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("second-creator"), ((AssignmentDto) response.getBody()).getCreatorUids());
        verify(assignmentRepo).save(existing);
    }

    @Test
    void resynchronizingTheSameListIsIdempotent() {
        Assignment existing = assignment(10L, "pilot", firstCreator);
        existing.setContentUrl(CANONICAL_CONTENT_URL);
        when(assignmentRepo.findFirstByContentUrlOrderByIdAsc(CANONICAL_CONTENT_URL)).thenReturn(existing);

        ResponseEntity<?> response = autoCreate(
            caller("pages-bot", "ROLE_ASSIGNMENT_SYNC"), List.of("AdityaS-2010"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("AdityaS-2010"), ((AssignmentDto) response.getBody()).getCreatorUids());
        verify(assignmentRepo, never()).save(any(Assignment.class));
    }

    @Test
    void anAssignmentSyncedWithoutCreatorsStaysValid() {
        // This is the browser's existing call shape: no creatorUids parameter at all.
        ResponseEntity<?> response = autoCreate(caller("sneaky-student", "ROLE_STUDENT"), null);

        assertEquals(201, response.getStatusCode().value());
        assertNull(((AssignmentDto) response.getBody()).getCreatorUids());
        verify(assignmentRepo).save(any(Assignment.class));
    }

    @Test
    void managedAssignmentsReturnsOnlyTheAssignmentsACreatorOwns() {
        Assignment owned = assignment(10L, "pilot", firstCreator);
        when(assignmentRepo.findByCreatorId(1L)).thenReturn(List.of(owned));

        ResponseEntity<?> response = controller.getManagedAssignments(caller("AdityaS-2010", "ROLE_STUDENT"));

        assertEquals(200, response.getStatusCode().value());
        List<AssignmentDto> dtos = assignmentDtos(response);
        assertEquals(1, dtos.size());
        assertEquals(10L, dtos.get(0).getId());
        assertEquals(List.of("AdityaS-2010"), dtos.get(0).getCreatorUids());
    }

    @Test
    void managedAssignmentsIsEmptyForAStudentWhoOwnsNothing() {
        Person nobody = student(7L, "no-assignments");
        when(personRepo.findByUid("no-assignments")).thenReturn(nobody);
        when(assignmentRepo.findByCreatorId(7L)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getManagedAssignments(caller("no-assignments", "ROLE_STUDENT"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(assignmentDtos(response).isEmpty());
    }

    @Test
    void managedAssignmentsReturnsEverythingForStaff() {
        Person toby = admin(4L, "toby");
        when(personRepo.findByUid("toby")).thenReturn(toby);
        when(assignmentRepo.findAllWithCreators())
            .thenReturn(List.of(assignment(10L, "pilot", firstCreator), assignment(11L, "other")));

        ResponseEntity<?> response = controller.getManagedAssignments(caller("toby", "ROLE_ADMIN"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, assignmentDtos(response).size());
        verify(assignmentRepo, never()).findByCreatorId(any());
    }

    @Test
    void managedAssignmentsRequiresAuthentication() {
        ResponseEntity<?> response = controller.getManagedAssignments(null);
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void bulkCreateBodyDeserializes() throws Exception {
        AssignmentDto[] dtos = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
            "[{\"name\":\"a\",\"type\":\"homework\",\"points\":1,\"dueDate\":\"2026-12-31\",\"assignmentType\":\"link\"}]",
            AssignmentDto[].class);
        assertEquals("link", dtos[0].assignmentType);
    }

    @Test
    void extractUnknownAssignmentReturns404() {
        when(personRepo.findByUid("boss")).thenReturn(admin(50L, "boss"));
        when(assignmentRepo.findById(99L)).thenReturn(java.util.Optional.empty());
        assertEquals(404, controller.extractAssignment(99L, caller("boss", "ROLE_ADMIN")).getStatusCode().value());
    }

    /** Tracker endpoints and the teacher/admin checks added for the admin UI revamp. */
    @Nested
    class TrackerAdmin {
        private final UserDetails studentCaller = caller("AdityaS-2010", "ROLE_STUDENT");

        @Test
        void studentsCannotDeleteOrManageGraders() {
            assertForbidden(() -> controller.deleteAssignment(1L, studentCaller));
            assertForbidden(() -> controller.assignGradersToAssignment(1L, List.of(2L), studentCaller));
            assertForbidden(() -> controller.randomizePeerGraders(1L, studentCaller));
            assertForbidden(() -> controller.bulkExtractAssignments(studentCaller));
            assertForbidden(() -> controller.trackerAssignments(studentCaller));
            assertForbidden(() -> controller.createTestAssignment(studentCaller));
            verify(assignmentRepo, never()).delete(any());
        }

        @Test
        void testAssignmentPassesItsOwnChecks() {
            when(personRepo.findByUid("boss")).thenReturn(admin(50L, "boss"));
            ResponseEntity<?> response = controller.createTestAssignment(caller("boss", "ROLE_ADMIN"));

            assertEquals(201, response.getStatusCode().value());
            org.mockito.ArgumentCaptor<Assignment> saved = org.mockito.ArgumentCaptor.forClass(Assignment.class);
            verify(assignmentRepo).save(saved.capture());
            Assignment created = saved.getValue();
            created.setAiRubric("Score 0.9: great\nScore 0.5: ok\nScore 0: missing");
            List<AssignmentRubricService.Check> checks = AssignmentRubricService.runChecks(created, List.of());
            assertTrue(checks.stream().allMatch(AssignmentRubricService.Check::ok), checks.toString());
        }

        @Test
        void checksFlagBrokenAssignment() {
            Assignment broken = assignment(7L, "Broken");
            broken.setPoints(null);
            broken.setDueDate("2026-12-31");
            broken.setAssignmentType(null);
            AssignmentSubmission overGraded = AssignmentCreatorFixtures.submission(1L, broken);
            overGraded.setGrade(4.0);
            overGraded.setNeedsAiRegrade(true);

            List<String> failed = AssignmentRubricService.runChecks(broken, List.of(overGraded)).stream()
                .filter(check -> !check.ok()).map(AssignmentRubricService.Check::check).toList();

            assertTrue(failed.containsAll(List.of("Points", "Due date", "Submission type",
                "AI rubric", "Grades in range", "AI queue")), failed.toString());
        }

        private void assertForbidden(org.junit.jupiter.api.function.Executable call) {
            org.springframework.web.server.ResponseStatusException e =
                org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, call);
            assertEquals(403, e.getStatusCode().value());
        }
    }

    /** Rubric readiness (AssignmentRubricService) and the AI re-grade job built on it. */
    @Nested
    class RegradeService {
        private static final String READY_RUBRIC = """
                Score 0.9 — Strong / Exceptional
                Great.

                **Score 0.75 — Adequate**
                Fine.

                Score 0.5 — Limited
                Thin.
                """;

        @BeforeEach
        void openRegradeMocks() {
            MockitoAnnotations.openMocks(this);
            // The conditional writes themselves are covered against SQLite in
            // SubmissionGradingPersistenceTest; here a graded result is simply applied.
            when(gradingService.recordAiGrade(any(), anyInt(), any())).thenAnswer(call -> GradingResult.of(
                    GradingOutcome.AI_GRADE_APPLIED, aiGradedState(call.getArgument(0), call.getArgument(2))));
            // Every assignment here is open.
            when(gradingService.currentState(any())).thenAnswer(call -> java.util.Optional.of(new SubmissionGradingState(
                    call.getArgument(0), 1, null, null, null, null, null, null, false, null, null, true, null)));
        }

        @Mock AssignmentSubmissionJPA submissionRepo;
        @Mock AssignmentJpaRepository assignmentRepo;
        @Mock AssignmentAiGradingService aiGradingService;
        @Mock SubmissionGradingService gradingService;
        @Mock AssignmentRubricService rubricService;
        @Mock JdbcTemplate jdbcTemplate;

        // ---- rubric readiness ----

        @Test
        void defaultOrBlankRubricIsNeverReady() {
            assertFalse(AssignmentRubricService.isAiRubricReady(Assignment.DEFAULT_AI_RUBRIC, 1.0));
            assertFalse(AssignmentRubricService.isAiRubricReady("  \n" + Assignment.DEFAULT_AI_RUBRIC + "\n", 1.0));
            assertFalse(AssignmentRubricService.isAiRubricReady(null, 1.0));
            assertFalse(AssignmentRubricService.isAiRubricReady("Be thorough and explain your reasoning.", 1.0));
        }

        @Test
        void acceptsTiersUpToNinetyPercentIncludingMarkdownHeadings() {
            assertTrue(AssignmentRubricService.isAiRubricReady(READY_RUBRIC, 1.0));
            assertTrue(AssignmentRubricService.isAiRubricReady(READY_RUBRIC, null));
        }

        @Test
        void rejectsTiersAboveNinetyPercentOfPoints() {
            assertFalse(AssignmentRubricService.isAiRubricReady("Score 1.0 — Strong\nx\nScore 0.8 — Adequate\n", 1.0));
            String fourPoint = "Score 4 — Strong\nx\nScore 3 — Adequate\nx\nScore 2 — Limited\n";
            assertFalse(AssignmentRubricService.isAiRubricReady(fourPoint, 1.0));
            // An old 1-4 rubric is only trusted when its top tier matches 90% of the points.
            assertFalse(AssignmentRubricService.isAiRubricReady(fourPoint, 5.0));
            assertFalse(AssignmentRubricService.isAiRubricReady(fourPoint, 100.0));
            assertTrue(AssignmentRubricService.isAiRubricReady("Score 4.5 — Strong\nx\nScore 3 — Adequate\n", 5.0));
            assertEquals(0.9, AssignmentRubricService.aiTopScore(AssignmentRubricService.maxScore(null)));
        }

        // ---- re-grade job ----

        @Test
        void generatesMissingRubricThenRegradesAndClearsFlag() throws Exception {
            Assignment assignment = assignment(Assignment.DEFAULT_AI_RUBRIC);
            flagged(10L);
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment));
            when(rubricService.generateRubric(eq("Inheritance"), anyString(), isNull(), eq(1.0))).thenReturn(READY_RUBRIC);
            when(submissionRepo.findById(10L)).thenReturn(Optional.of(submission(10L, true)));
            AssignmentAiGradingService.GradeResult graded = AssignmentAiGradingService.GradeResult.graded(0.85, 3, "Good.", false);
            when(aiGradingService.grade(any())).thenReturn(graded);

            service().runOnce();

            verify(jdbcTemplate).update("UPDATE assignment SET ai_rubric = ? WHERE id = ?", READY_RUBRIC, 1L);
            verify(gradingService).recordAiGrade(10L, AssignmentSubmission.FIRST_REVISION, graded);
            verify(submissionRepo, never()).save(any());
        }

        @Test
        void leavesSubmissionsFlaggedWhenRubricStillUnavailable() throws Exception {
            flagged(10L);
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment(Assignment.DEFAULT_AI_RUBRIC)));
            when(rubricService.generateRubric(any(), any(), any(), any())).thenReturn(null);

            service().runOnce();

            verify(aiGradingService, never()).grade(any());
            verifyNoInteractions(gradingService);
        }

        @Test
        void skipsASubmissionThatLeftTheQueueBeforeItsTurn() throws Exception {
            // e.g. a teacher graded it by hand, which cancels the pending re-grade.
            flagged(10L);
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment(READY_RUBRIC)));
            when(submissionRepo.findById(10L)).thenReturn(Optional.of(submission(10L, false)));

            service().runOnce();

            verify(aiGradingService, never()).grade(any());
            verifyNoInteractions(gradingService);
        }

        @Test
        void aTeacherGradeMadeDuringRegradeStaysEffective() throws Exception {
            flagged(10L);
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment(READY_RUBRIC)));
            when(submissionRepo.findById(10L)).thenReturn(Optional.of(submission(10L, true)));
            when(aiGradingService.grade(any()))
                    .thenReturn(AssignmentAiGradingService.GradeResult.graded(0.85, 3, "Good.", false));
            org.mockito.Mockito.doReturn(GradingResult.of(GradingOutcome.MANUAL_OVERRIDE_KEPT,
                    new SubmissionGradingState(10L, 1, 0.95, "Teacher", null, 0.85, "Good.", 3, true, 0.95, "Teacher", false, null)))
                    .when(gradingService).recordAiGrade(any(), anyInt(), any());

            service().runOnce();

            // Recording is the only write; it is the conditional one that keeps the manual grade.
            verify(gradingService).recordAiGrade(eq(10L), eq(AssignmentSubmission.FIRST_REVISION), any());
            verify(submissionRepo, never()).save(any());
        }

        @Test
        void aNotGradeableSubmissionLeavesTheQueue() throws Exception {
            flagged(10L);
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment(READY_RUBRIC)));
            when(submissionRepo.findById(10L)).thenReturn(Optional.of(submission(10L, true)));
            when(aiGradingService.grade(any()))
                    .thenReturn(AssignmentAiGradingService.GradeResult.notGradeable("No readable content"));

            service().runOnce();

            verify(gradingService).clearAiRegradeFlag(10L, AssignmentSubmission.FIRST_REVISION);
            verify(gradingService, never()).recordAiGrade(any(), anyInt(), any());
        }

        @Test
        void aFailedRegradeStaysQueuedForTheNextRun() throws Exception {
            flagged(10L);
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment(READY_RUBRIC)));
            when(submissionRepo.findById(10L)).thenReturn(Optional.of(submission(10L, true)));
            when(aiGradingService.grade(any()))
                    .thenReturn(AssignmentAiGradingService.GradeResult.failed("Gemini returned HTTP 503"));

            service().runOnce();

            verify(gradingService, never()).clearAiRegradeFlag(any(), anyInt());
            verify(gradingService, never()).flagForAiRegrade(any(), anyInt());
            verify(gradingService, never()).recordAiGrade(any(), anyInt(), any());
        }

        // ---- fix out-of-range grades ----

        @Test
        void fixOutOfRangeGradesAttemptsEachSubmissionOnceAndNeverFlagsFailures() throws Exception {
            when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                    Map.of("id", 10L, "assignment_id", 1L), Map.of("id", 11L, "assignment_id", 1L)));
            when(assignmentRepo.findById(1L)).thenReturn(Optional.of(assignment(READY_RUBRIC)));
            AssignmentSubmission good = submission(10L, false);
            AssignmentSubmission bad = submission(11L, false);
            when(submissionRepo.findById(10L)).thenReturn(Optional.of(good));
            when(submissionRepo.findById(11L)).thenReturn(Optional.of(bad));
            when(aiGradingService.grade(any())).thenAnswer(call -> ((AssignmentSubmission) call.getArgument(0)).getId() == 10L
                    ? AssignmentAiGradingService.GradeResult.graded(0.8, 3, "Good.", false)
                    : AssignmentAiGradingService.GradeResult.failed("Gemini returned HTTP 503"));

            AssignmentAiRegradeService service = service();
            assertEquals(2, service.startFixOutOfRangeGrades().total());
            long deadline = System.currentTimeMillis() + 5000;
            while (service.getFixStatus().running() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            assertEquals(new AssignmentAiRegradeService.FixGradesStatus(false, 2, 2, 1, 1), service.getFixStatus());
            verify(aiGradingService, org.mockito.Mockito.times(2)).grade(any());
            verify(gradingService).recordAiGrade(eq(10L), eq(AssignmentSubmission.FIRST_REVISION), any());
            verify(gradingService, never()).recordAiGrade(eq(11L), anyInt(), any());
            verify(gradingService, never()).flagForAiRegrade(any(), anyInt());
        }

        private void flagged(Long submissionId) {
            when(jdbcTemplate.queryForList(anyString()))
                    .thenReturn(List.of(Map.of("id", submissionId, "assignment_id", 1L)));
        }

        private AssignmentAiRegradeService service() {
            return new AssignmentAiRegradeService(submissionRepo, assignmentRepo,
                    new SubmissionAiGrader(aiGradingService, gradingService, org.mockito.Mockito.mock(GradeReviewService.class)), gradingService, rubricService, jdbcTemplate);
        }

        private static SubmissionGradingState aiGradedState(Long id, AssignmentAiGradingService.GradeResult result) {
            return new SubmissionGradingState(id, AssignmentSubmission.FIRST_REVISION, result.grade(), result.feedback(),
                    null, result.grade(), result.feedback(), result.qualityScore(), false, null, null, false, null);
        }

        private static Assignment assignment(String rubric) {
            Assignment assignment = new Assignment("Inheritance", "homework", "Explain inheritance", 1.0, "2026-12-31", "text");
            assignment.setId(1L);
            assignment.setAiRubric(rubric);
            return assignment;
        }

        private static AssignmentSubmission submission(Long id, boolean needsRegrade) {
            AssignmentSubmission submission = new AssignmentSubmission();
            submission.setId(id);
            submission.setContent(new HashMap<>(Map.of("type", "text", "text", "answer")));
            submission.setNeedsAiRegrade(needsRegrade);
            return submission;
        }
    }

    @SuppressWarnings("unchecked")
    private List<AssignmentDto> assignmentDtos(ResponseEntity<?> response) {
        return (List<AssignmentDto>) response.getBody();
    }
}
