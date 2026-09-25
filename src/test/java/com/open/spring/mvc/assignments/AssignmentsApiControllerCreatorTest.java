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
        return controller.autoCreateAssignment(
            "Sample Assignment", CONTENT_URL, "", null, null,
            null, null, creatorUids, courseCodes, caller);
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
            null, null, null, null, null, null, caller("admin", "ROLE_ADMIN"));

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

    @SuppressWarnings("unchecked")
    private List<AssignmentDto> assignmentDtos(ResponseEntity<?> response) {
        return (List<AssignmentDto>) response.getBody();
    }
}
