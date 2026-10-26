package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.assignments.AssignmentsApiController.TrackerAssignmentDto;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Covers the admin tracker's data endpoints: GET /api/assignments/tracker,
 * POST /api/assignments/{id}/ai-rubric/regenerate and GET /api/assignments/{id}/checks.
 */
class AssignmentTrackerApiTest {

    private static final String READY_RUBRIC = "Score 0.9: great\nScore 0.5: ok\nScore 0: missing";

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private GroupsJpaRepository groupsRepository;

    @Mock
    private AssignmentRubricService rubricService;

    @Mock
    private AssignmentAiRegradeService aiRegradeService;

    private AssignmentsApiController controller;

    private final UserDetails toby = User.withUsername("toby").password("ignored").authorities("ROLE_ADMIN").build();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        CourseGroupProperties courses = new CourseGroupProperties();
        courses.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));

        controller = new AssignmentsApiController();
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(controller, "rubricService", rubricService);
        ReflectionTestUtils.setField(controller, "aiRegradeService", aiRegradeService);
        ReflectionTestUtils.setField(controller, "assignmentCourseSyncService",
            new AssignmentCourseSyncService(groupsRepository, courses));
        when(personRepo.findByUid("toby")).thenReturn(admin(1L, "toby"));
    }

    private static Map<String, Object> statsRow(long assignmentId, long total, long ungraded, Object pendingAi,
            Double average) {
        Map<String, Object> row = new HashMap<>();
        row.put("assignment_id", assignmentId);
        row.put("total", total);
        row.put("ungraded", ungraded);
        row.put("pending_ai", pendingAi);
        row.put("average_grade", average);
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void trackerJoinsSubmissionStatsOntoEveryAssignment() {
        Assignment graded = assignment(10L, "Arrays");
        graded.setAiRubric(READY_RUBRIC);
        Assignment empty = assignment(11L, "Loops");
        when(assignmentRepo.findAll()).thenReturn(List.of(graded, empty));
        // SQLite returns Integer counts and MySQL returns BigDecimal sums; both must map.
        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(statsRow(10L, 3L, 1L, new java.math.BigDecimal(2), 0.75)));

        ResponseEntity<?> response = controller.trackerAssignments(toby);

        assertEquals(200, response.getStatusCode().value());
        List<TrackerAssignmentDto> rows = (List<TrackerAssignmentDto>) response.getBody();
        TrackerAssignmentDto arrays = rows.get(0);
        assertEquals(3, arrays.submissionCount);
        assertEquals(1, arrays.ungradedCount);
        assertEquals(2, arrays.pendingAiCount);
        assertEquals(0.75, arrays.averageGrade);
        assertTrue(arrays.rubricReady);

        TrackerAssignmentDto loops = rows.get(1);
        assertEquals(0, loops.submissionCount);
        assertNull(loops.averageGrade);
        assertFalse(loops.rubricReady);
    }

    @Test
    void regenerateRubricOnAMissingAssignmentIs404() {
        when(assignmentRepo.findById(99L)).thenReturn(Optional.empty());

        assertEquals(404, controller.regenerateAiRubric(99L, toby).getStatusCode().value());
        verify(rubricService, never()).generateRubric(any(), any(), any(), any());
    }

    @Test
    void regenerateRubricReportsAnAiOutageAs502AndKeepsTheOldRubric() {
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(assignment(10L, "Arrays")));
        when(rubricService.generateRubric(any(), any(), any(), any())).thenReturn(null);

        assertEquals(502, controller.regenerateAiRubric(10L, toby).getStatusCode().value());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(aiRegradeService, never()).requestRubric(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void regenerateRubricUpdatesOnlyTheRubricColumnThenRequeues() {
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(assignment(10L, "Arrays")));
        when(rubricService.generateRubric(eq("Arrays"), any(), isNull(), eq(1.0))).thenReturn(READY_RUBRIC);

        ResponseEntity<?> response = controller.regenerateAiRubric(10L, toby);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, ((Map<String, Object>) response.getBody()).get("rubricReady"));
        verify(jdbcTemplate).update("UPDATE assignment SET ai_rubric = ? WHERE id = ?", READY_RUBRIC, 10L);
        verify(assignmentRepo, never()).save(any());
        verify(aiRegradeService).requestRubric(eq(10L), eq("Arrays"), any(), isNull());
    }

    @Test
    void checksOnAMissingAssignmentIs404() {
        when(assignmentRepo.findById(99L)).thenReturn(Optional.empty());

        assertEquals(404, controller.checkAssignment(99L, toby).getStatusCode().value());
    }

    @Test
    void zeroPointsPassesTheDetailsEditButFailsThePointsCheck() {
        Assignment arrays = assignment(10L, "Arrays");
        when(assignmentRepo.findById(10L)).thenReturn(Optional.of(arrays));
        when(assignmentRepo.save(any(Assignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AssignmentsApiController.AssignmentUpdateRequest edit = new AssignmentsApiController.AssignmentUpdateRequest();
        edit.name = "Arrays";
        edit.type = "hw";
        edit.points = 0.0;
        edit.dueDate = "12/31/2026";

        assertEquals(200, controller.updateAssignmentDetails(10L, edit, toby).getStatusCode().value());
        boolean pointsOk = AssignmentRubricService.runChecks(arrays, List.of()).stream()
            .filter(check -> check.check().equals("Points")).findFirst().orElseThrow().ok();
        // Documents the current gap reported in the test report: the edit accepts 0, the check rejects it.
        assertFalse(pointsOk);
    }
}
