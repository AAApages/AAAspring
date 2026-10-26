package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.teacher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Covers the AI grading queue endpoints behind the admin UI: GET /api/submissions/ai-queue,
 * POST /api/submissions/ai-queue/{id}/grade-now and POST /api/submissions/ai-queue/run-now.
 */
class AssignmentAiQueueApiTest {

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private GroupsJpaRepository groupRepo;

    @Mock
    private AssignmentAiRegradeService aiRegradeService;

    private AssignmentSubmissionAPIController controller;

    private final Person hop = student(3L, "hop");
    private final Person other = student(4L, "other");
    private final Person niko = teacher(2L, "niko");

    /** hop's own submission, hop's group's submission, and someone else's. */
    private final List<AssignmentAiRegradeService.QueueEntry> queue = List.of(
        entry(100L, 3L, 1),
        entry(101L, 40L, 2),
        entry(102L, 4L, 3));

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AssignmentSubmissionAPIController();
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "groupRepo", groupRepo);
        ReflectionTestUtils.setField(controller, "aiRegradeService", aiRegradeService);
        ReflectionTestUtils.setField(
            controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());

        when(personRepo.findByUid("hop")).thenReturn(hop);
        when(personRepo.findByUid("other")).thenReturn(other);
        when(personRepo.findByUid("niko")).thenReturn(niko);
        Groups hopsGroup = new Groups();
        ReflectionTestUtils.setField(hopsGroup, "id", 40L);
        when(groupRepo.findGroupsByPersonId(3L)).thenReturn(List.of(hopsGroup));
        when(groupRepo.findGroupsByPersonId(4L)).thenReturn(List.of());
        when(aiRegradeService.queueSnapshot())
            .thenReturn(new AssignmentAiRegradeService.QueueSnapshot(false, 120, 4.0, queue));
    }

    private static AssignmentAiRegradeService.QueueEntry entry(long submissionId, long submitterId, int position) {
        return new AssignmentAiRegradeService.QueueEntry(submissionId, 10L, "Arrays", submitterId,
            "Submitter " + submitterId, null, List.of(), position, 120 + position * 4L, false);
    }

    private UserDetails caller(String uid) {
        return User.withUsername(uid).password("ignored").authorities(List.of()).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Long> entryIds(ResponseEntity<?> response) {
        return ((List<AssignmentAiRegradeService.QueueEntry>) body(response).get("entries")).stream()
            .map(AssignmentAiRegradeService.QueueEntry::submissionId).toList();
    }

    @Test
    void anonymousQueueIsUnauthorized() {
        assertEquals(401, controller.aiQueue(null).getStatusCode().value());
    }

    @Test
    void studentSeesOwnAndGroupEntriesButTheFullQueueLength() {
        ResponseEntity<?> response = controller.aiQueue(caller("hop"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(100L, 101L), entryIds(response));
        assertEquals(3, body(response).get("queueLength"));
        assertEquals(120L, body(response).get("nextRunInSeconds"));
    }

    @Test
    void studentWithNothingQueuedSeesNoEntries() {
        assertEquals(List.of(102L), entryIds(controller.aiQueue(caller("other"))));
    }

    @Test
    void teacherSeesEveryEntry() {
        assertEquals(List.of(100L, 101L, 102L), entryIds(controller.aiQueue(caller("niko"))));
    }

    @Test
    void studentsCannotSkipOrRunTheQueue() {
        assertEquals(403, controller.gradeQueuedNow(100L, caller("hop")).getStatusCode().value());
        assertEquals(403, controller.runAiQueueNow(caller("hop")).getStatusCode().value());
        assertEquals(401, controller.runAiQueueNow(null).getStatusCode().value());
        verify(aiRegradeService, never()).gradeNow(anyLong());
        verify(aiRegradeService, never()).runQueueNow();
    }

    @Test
    void gradeNowMapsEachResultToItsStatus() {
        Map<String, Integer> expected = Map.of("graded", 200, "busy", 409, "not_queued", 409, "failed", 502);
        expected.forEach((status, code) -> {
            when(aiRegradeService.gradeNow(100L))
                .thenReturn(new AssignmentAiRegradeService.GradeNowResult(status, status));
            assertEquals(code, controller.gradeQueuedNow(100L, caller("niko")).getStatusCode().value(), status);
        });
    }

    @Test
    void runNowStartsOnceAndRejectsASecondRun() {
        when(aiRegradeService.runQueueNow()).thenReturn(true, false);

        assertEquals(202, controller.runAiQueueNow(caller("niko")).getStatusCode().value());
        assertEquals(409, controller.runAiQueueNow(caller("niko")).getStatusCode().value());
    }
}
