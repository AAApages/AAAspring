package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.assignments.AssignmentSubmissionAPIController.SubmitAssignmentDto;
import com.open.spring.mvc.assignments.AssignmentsApiController.AssignmentDto;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Full create/read/update/delete pass with the seeded test student "hop" and admin "toby",
 * mirroring api/assignments_gradebook.http: toby creates a [TEST] assignment, hop submits,
 * reads and edits it and is refused the staff actions, toby grades and cleans up. The
 * repositories are backed by in-memory maps so each step sees the previous step's writes.
 */
class HopSubmissionLifecycleTest {

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private GroupsJpaRepository groupRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AssignmentAiGradingService aiGradingService;

    @Mock
    private AssignmentAiRegradeService aiRegradeService;

    private AssignmentsApiController assignments;
    private AssignmentSubmissionAPIController submissions;

    private final Map<Long, Assignment> assignmentTable = new LinkedHashMap<>();
    private final Map<Long, AssignmentSubmission> submissionTable = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(500);

    private final Person hop = student(3L, "hop");
    private final Person toby = admin(1L, "toby");
    private final UserDetails asHop = caller("hop", "ROLE_STUDENT");
    private final UserDetails asToby = caller("toby", "ROLE_ADMIN");

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(personRepo.findByUid("hop")).thenReturn(hop);
        when(personRepo.findByUid("toby")).thenReturn(toby);
        when(personRepo.findById(3L)).thenReturn(Optional.of(hop));
        when(groupRepo.findGroupsByPersonId(anyLong())).thenReturn(List.of());

        when(assignmentRepo.save(any(Assignment.class))).thenAnswer(invocation -> {
            Assignment assignment = invocation.getArgument(0);
            if (assignment.getId() == null) {
                assignment.setId(nextId.getAndIncrement());
            }
            assignmentTable.put(assignment.getId(), assignment);
            return assignment;
        });
        when(assignmentRepo.findById(anyLong()))
            .thenAnswer(invocation -> Optional.ofNullable(assignmentTable.get(invocation.<Long>getArgument(0))));
        doAnswer(invocation -> assignmentTable.remove(invocation.<Assignment>getArgument(0).getId()))
            .when(assignmentRepo).delete(any(Assignment.class));

        when(submissionRepo.save(any(AssignmentSubmission.class))).thenAnswer(invocation -> {
            AssignmentSubmission submission = invocation.getArgument(0);
            if (submission.getId() == null) {
                submission.setId(nextId.getAndIncrement());
            }
            submissionTable.put(submission.getId(), submission);
            return submission;
        });
        when(submissionRepo.findById(anyLong()))
            .thenAnswer(invocation -> Optional.ofNullable(submissionTable.get(invocation.<Long>getArgument(0))));
        when(submissionRepo.findBySubmitterId(anyLong())).thenAnswer(invocation -> submissionTable.values().stream()
            .filter(s -> s.getSubmitter() != null && s.getSubmitter().getId().equals(invocation.getArgument(0)))
            .toList());
        doAnswer(invocation -> submissionTable.remove(invocation.<AssignmentSubmission>getArgument(0).getId()))
            .when(submissionRepo).delete(any(AssignmentSubmission.class));

        // The AI grader is down: submissions must still save, flagged for the background re-grade.
        when(aiGradingService.grade(any())).thenReturn(AssignmentAiGradingService.GradeResult.failed("offline"));

        CourseGroupProperties courses = new CourseGroupProperties();
        courses.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
        AssignmentAuthorizationService authorization = new AssignmentAuthorizationService();

        assignments = new AssignmentsApiController();
        ReflectionTestUtils.setField(assignments, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(assignments, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(assignments, "personRepo", personRepo);
        ReflectionTestUtils.setField(assignments, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(assignments, "aiRegradeService", aiRegradeService);
        ReflectionTestUtils.setField(assignments, "assignmentAuthorizationService", authorization);
        ReflectionTestUtils.setField(assignments, "assignmentCourseSyncService",
            new AssignmentCourseSyncService(groupRepo, courses));

        submissions = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(submissions, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(submissions, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(submissions, "personRepo", personRepo);
        ReflectionTestUtils.setField(submissions, "groupRepo", groupRepo);
        ReflectionTestUtils.setField(submissions, "aiGradingService", aiGradingService);
        ReflectionTestUtils.setField(submissions, "aiRegradeService", aiRegradeService);
        ReflectionTestUtils.setField(submissions, "assignmentAuthorizationService", authorization);
    }

    private static UserDetails caller(String uid, String role) {
        return User.withUsername(uid).password("ignored").authorities(role).build();
    }

    private static SubmitAssignmentDto textSubmission(Long submitterId, String text) {
        SubmitAssignmentDto dto = new SubmitAssignmentDto();
        dto.submitterId = submitterId;
        dto.isGroup = false;
        dto.content = Map.of("type", "text", "text", text);
        dto.comment = "from HopSubmissionLifecycleTest";
        return dto;
    }

    private Long createTestAssignment() {
        ResponseEntity<?> created = assignments.createTestAssignment(asToby);
        assertEquals(201, created.getStatusCode().value());
        return ((AssignmentDto) created.getBody()).getId();
    }

    @Test
    void hopCanRunTheWholeSubmissionLifecycle() {
        // CREATE (admin): a valid throwaway assignment.
        Long assignmentId = createTestAssignment();
        Assignment assignment = assignmentTable.get(assignmentId);
        assertTrue(assignment.getName().startsWith("[TEST] "));
        assertEquals("text", assignment.getAssignmentType());

        // CREATE (hop): the submission is kept even though the AI grader failed.
        ResponseEntity<?> submitted = submissions.submitAssignment(
            assignmentId, textSubmission(3L, "Today I learned about REST."), asHop);
        assertEquals(201, submitted.getStatusCode().value());
        Long submissionId = ((AssignmentSubmissionReturnDto) submitted.getBody()).id;
        AssignmentSubmission stored = submissionTable.get(submissionId);
        assertNull(stored.getGrade());
        assertTrue(stored.getNeedsAiRegrade(), "a failed AI grade should queue a re-grade, not invent a grade");

        // READ (hop): own submissions.
        ResponseEntity<?> mine = submissions.getSubmissions(3L);
        assertEquals(200, mine.getStatusCode().value());
        assertEquals(1, ((List<?>) mine.getBody()).size());

        // UPDATE (hop): resubmitting with the same content type the assignment allows.
        // Text assignments can't be edited through PUT /api/submissions/{id} (link/file only).
        ResponseEntity<?> edit = submissions.updateSubmission(
            submissionId, "text", null, "new notes", "edited", false, null, asHop);
        assertEquals(400, edit.getStatusCode().value());

        // Staff-only actions are refused for hop.
        assertEquals(403, submissions.gradeSubmission(submissionId, asHop, 1.0, "self").getStatusCode().value());
        assertEquals(403, submissions.deleteSubmission(submissionId, asHop).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> assignments.deleteAssignment(assignmentId, asHop)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> assignments.checkAssignment(assignmentId, asHop)).getStatusCode().value());

        // UPDATE (admin): a teacher grade is final and leaves the AI queue.
        ResponseEntity<?> graded = submissions.gradeSubmission(submissionId, asToby, 0.8, "Nice work");
        assertEquals(200, graded.getStatusCode().value());
        assertEquals(0.8, stored.getGrade());
        assertEquals("Nice work", stored.getFeedback());
        assertFalse(stored.getNeedsAiRegrade());
        assertNotNull(stored.getGradedAt(), "gradedAt drives the gradebook's recent changes");

        // DELETE (admin): submission, then assignment; reads afterwards are 404.
        assertEquals(200, submissions.deleteSubmission(submissionId, asToby).getStatusCode().value());
        assertTrue(submissionTable.isEmpty());
        assertEquals(200, assignments.deleteAssignment(assignmentId, asToby).getStatusCode().value());
        assertEquals(404, assignments.getById(assignmentId).getStatusCode().value());
        assertEquals(404, assignments.deleteAssignment(assignmentId, asToby).getStatusCode().value());
    }

    @Test
    void hopCannotSubmitAsSomeoneElse() {
        Long assignmentId = createTestAssignment();
        Person grace = student(9L, "grace");
        when(personRepo.findById(9L)).thenReturn(Optional.of(grace));

        ResponseEntity<?> response = submissions.submitAssignment(
            assignmentId, textSubmission(9L, "not mine"), asHop);

        assertEquals(403, response.getStatusCode().value());
        assertTrue(submissionTable.isEmpty());
    }

    @Test
    void hopCannotSubmitTheWrongContentType() {
        Long assignmentId = createTestAssignment();
        SubmitAssignmentDto link = textSubmission(3L, "unused");
        link.content = Map.of("type", "link", "url", "https://example.com");

        assertEquals(400, submissions.submitAssignment(assignmentId, link, asHop).getStatusCode().value());
        assertTrue(submissionTable.isEmpty());
    }

    @Test
    void submittingToAMissingAssignmentIs404() {
        assertEquals(404, submissions.submitAssignment(424242L, textSubmission(3L, "x"), asHop)
            .getStatusCode().value());
    }

    @Test
    void aTeacherGradeAboveThePointsIsAcceptedThenFlaggedByChecks() {
        Long assignmentId = createTestAssignment();
        Long submissionId = ((AssignmentSubmissionReturnDto) submissions.submitAssignment(
            assignmentId, textSubmission(3L, "work"), asHop).getBody()).id;

        // Documents the gap in the report: POST /grade has no range check on a 1-point assignment.
        assertEquals(200, submissions.gradeSubmission(submissionId, asToby, 5.0, null).getStatusCode().value());
        boolean inRange = AssignmentRubricService.runChecks(assignmentTable.get(assignmentId),
                List.copyOf(submissionTable.values())).stream()
            .filter(check -> check.check().equals("Grades in range")).findFirst().orElseThrow().ok();
        assertFalse(inRange);
    }
}
