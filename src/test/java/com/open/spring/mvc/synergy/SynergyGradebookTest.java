package com.open.spring.mvc.synergy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentAiRegradeService;
import com.open.spring.mvc.assignments.AssignmentJpaRepository;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.PersonRole;

/** Gradebook card view (GradebookService) and the grade-request / card access rules. */
class SynergyGradebookTest {

    @Mock private GroupsJpaRepository groupsRepository;
    @Mock private AssignmentSubmissionJPA submissionRepository;
    @Mock private AssignmentAiRegradeService aiRegradeService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private PersonJpaRepository personRepository;
    @Mock private AssignmentJpaRepository assignmentRepository;
    @Mock private SynergyGradeRequestJpaRepository gradeRequestRepository;

    private GradebookService service;
    private SynergyApiController controller;

    private final Person alice = person(1L, "alice", "Alice Anders", "ROLE_STUDENT");
    private final Person bob = person(2L, "bob", "Bob Brown", "ROLE_STUDENT");
    private final Person teacher = person(3L, "mort", "Mr Mort", "ROLE_TEACHER");
    private final Assignment tenPoints = assignment(10L, "Arrays", 10.0);
    private final Assignment twoPoints = assignment(11L, "Loops", 2.0);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        CourseGroupProperties courses = new CourseGroupProperties();
        courses.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
        service = new GradebookService(groupsRepository, submissionRepository, aiRegradeService, courses, jdbcTemplate,
            personRepository);
        when(aiRegradeService.queueSnapshot())
            .thenReturn(new AssignmentAiRegradeService.QueueSnapshot(false, 60, 15, List.of()));
        when(groupsRepository.findGroupsByPersonId(anyLong())).thenReturn(List.of());
        when(submissionRepository.findBySubmitterId(anyLong())).thenReturn(List.of());

        controller = new SynergyApiController(groupsRepository);
        ReflectionTestUtils.setField(controller, "gradebookService", service);
        ReflectionTestUtils.setField(controller, "personRepository", personRepository);
        ReflectionTestUtils.setField(controller, "assignmentRepository", assignmentRepository);
        ReflectionTestUtils.setField(controller, "submissionRepository", submissionRepository);
        ReflectionTestUtils.setField(controller, "gradeRequestRepository", gradeRequestRepository);
        for (Person p : List.of(alice, bob, teacher)) {
            when(personRepository.findByUid(p.getUid())).thenReturn(p);
            when(personRepository.findById(p.getId())).thenReturn(Optional.of(p));
        }
        when(assignmentRepository.findById(10L)).thenReturn(Optional.of(tenPoints));
    }

    @Nested
    class Card {
        @Test
        void overallCountsOwnAndGroupSubmissionsAndSkipsUngraded() {
            Groups team = group(50L);
            when(groupsRepository.findGroupsByPersonId(1L)).thenReturn(List.of(team));
            when(submissionRepository.findBySubmitterId(1L)).thenReturn(List.of(submission(100L, tenPoints, 9.0)));
            AssignmentSubmission groupWork = submission(101L, twoPoints, 1.0);
            AssignmentSubmission queued = submission(102L, twoPoints, null);
            queued.setNeedsAiRegrade(true);
            when(submissionRepository.findBySubmitterId(50L)).thenReturn(List.of(groupWork, queued));
            when(aiRegradeService.queueSnapshot()).thenReturn(new AssignmentAiRegradeService.QueueSnapshot(
                false, 60, 15, List.of(new AssignmentAiRegradeService.QueueEntry(102L, 11L, "Loops", 50L, "Ada", "ada", List.of(), 1, 75, false))));

            GradebookService.StudentCard card = service.card(alice);

            assertEquals(83.3, card.overallPct()); // (9 + 1) / (10 + 2)
            assertEquals(3, card.submissions().size());
            GradebookService.SubmissionEntry pending = card.submissions().stream()
                .filter(s -> s.submissionId() == 102L).findFirst().orElseThrow();
            assertEquals("pending_ai", pending.status());
            assertEquals(75L, pending.etaSeconds());
        }

        @Test
        void noGradesMeansNoOverall() {
            when(submissionRepository.findBySubmitterId(1L)).thenReturn(List.of(submission(100L, tenPoints, null)));
            assertNull(service.card(alice).overallPct());
        }

        @Test
        void studentCannotOpenAnotherStudentsCard() {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.gradebookCard(caller(alice), 2L));
            assertEquals(403, e.getStatusCode().value());
            assertEquals(200, controller.gradebookCard(caller(alice), 1L).getStatusCode().value());
            assertEquals(200, controller.gradebookCard(caller(teacher), 2L).getStatusCode().value());
        }

        @Test
        void studentCannotListRoster() {
            assertThrows(ResponseStatusException.class, () -> controller.gradebookStudents(caller(alice), "CSA", null));
        }
    }

    @Test
    void rosterFiltersByNameAndCreditsGroupSubmissionsToMembers() {
        Groups csa = group(900L);
        when(groupsRepository.findByName("CSA")).thenReturn(Optional.of(csa));
        List<Object[]> members = new ArrayList<>();
        members.add(new Object[] {1L, "alice", "Alice Anders", "a@x"});
        members.add(new Object[] {2L, "bob", "Bob Brown", "b@x"});
        when(groupsRepository.findGroupMembersRaw(900L)).thenReturn(members);
        when(jdbcTemplate.queryForList(startsWith("SELECT group_id"))).thenReturn(List.of(
            Map.of("group_id", 50L, "person_id", 1L), Map.of("group_id", 50L, "person_id", 2L)));
        when(jdbcTemplate.queryForList(startsWith("SELECT s.submitter_id"))).thenReturn(List.of(
            row(1L, 5.0, 10.0, false), row(50L, 2.0, 2.0, false), row(2L, null, 1.0, true)));

        List<GradebookService.StudentRow> all = service.roster("csa", null);
        assertEquals(List.of("Alice Anders", "Bob Brown"), all.stream().map(GradebookService.StudentRow::name).toList());
        assertEquals(58.3, all.get(0).overallPct()); // (5 + 2) / (10 + 2)
        assertEquals(100.0, all.get(1).overallPct()); // group work only; the queued one isn't graded
        assertEquals(1, all.get(1).pendingAiCount());

        assertEquals(List.of("Bob Brown"),
            service.roster("CSA", "brow").stream().map(GradebookService.StudentRow::name).toList());
        assertThrows(IllegalArgumentException.class, () -> service.roster("MATH", null));
    }

    @Test
    void coursesFollowTabOrder() {
        assertEquals(List.of("CSSE", "CSP", "CSA", "CSH"), service.courses());
    }

    @Test
    void noClassRosterListsStudentsAndAdminsOutsideEveryClassGroup() {
        Groups csa = group(900L);
        when(groupsRepository.findByName("CSA")).thenReturn(Optional.of(csa));
        List<Object[]> members = new ArrayList<>();
        members.add(new Object[] {1L, "alice", "Alice Anders", "a@x"});
        when(groupsRepository.findGroupMembersRaw(900L)).thenReturn(members);
        when(personRepository.findPeopleWithRole("ROLE_TEACHER")).thenReturn(List.of(teacher));
        Person admin = person(4L, "toby", "Thomas Edison", "ROLE_ADMIN");
        when(personRepository.findPeopleWithRole("ROLE_ADMIN")).thenReturn(List.of(admin));
        when(jdbcTemplate.queryForList(startsWith("SELECT id, uid, name"))).thenReturn(List.of(
            Map.of("id", 1L, "uid", "alice", "name", "Alice Anders"),
            Map.of("id", 2L, "uid", "bob", "name", "Bob Brown"),
            Map.of("id", 3L, "uid", "mort", "name", "Mr Mort"),
            Map.of("id", 4L, "uid", "toby", "name", "Thomas Edison")));

        List<GradebookService.StudentRow> rows = service.roster("none", null);
        assertEquals(List.of("Bob Brown", "Thomas Edison"), rows.stream().map(GradebookService.StudentRow::name).toList());
        assertNull(rows.get(0).role());
        assertEquals("Admin", rows.get(1).role());
    }

    @Nested
    class GradeRequests {
        @Test
        void studentCannotRequestForSomeoneElse() {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.createGradeRequest(caller(alice), request(2L, null, 10L)));
            assertEquals(403, e.getStatusCode().value());
            verify(gradeRequestRepository, never()).save(any());
        }

        @Test
        void studentCannotRequestForAnUnsubmittedAssignment() {
            assertThrows(ResponseStatusException.class,
                () -> controller.createGradeRequest(caller(alice), request(1L, false, 10L)));
            verify(gradeRequestRepository, never()).save(any());
        }

        @Test
        void studentCanRequestForOwnSubmissionWithoutIsGroup() {
            when(submissionRepository.findBySubmitterId(1L)).thenReturn(List.of(submission(100L, tenPoints, 5.0)));
            assertEquals(200, controller.createGradeRequest(caller(alice), request(1L, null, 10L)).getStatusCode().value());
            verify(gradeRequestRepository, times(1)).save(any());
        }

        @Test
        void teacherCanRequestForAnyStudent() {
            assertEquals(200, controller.createGradeRequest(caller(teacher), request(2L, false, 10L)).getStatusCode().value());
            verify(gradeRequestRepository, times(1)).save(any());
        }
    }

    private static Person person(Long id, String uid, String name, String role) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(name);
        person.setRoles(new ArrayList<>(List.of("ROLE_USER", role)));
        return person;
    }

    private static Groups group(Long id) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        return group;
    }

    private static Assignment assignment(Long id, String name, Double points) {
        Assignment assignment = new Assignment(name, "test", "", points, "10/25/2026", "text");
        assignment.setId(id);
        return assignment;
    }

    private static AssignmentSubmission submission(Long id, Assignment assignment, Double grade) {
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setId(id);
        submission.setAssignment(assignment);
        submission.setGrade(grade);
        return submission;
    }

    private static Map<String, Object> row(Long submitter, Double grade, Double points, boolean queued) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("submitter_id", submitter);
        row.put("grade", grade);
        row.put("points", points);
        row.put("needs_ai_regrade", queued);
        return row;
    }

    private static UserDetails caller(Person person) {
        return User.withUsername(person.getUid()).password("ignored").authorities("ROLE_USER").build();
    }

    private static SynergyApiController.SynergyGradeRequestDto request(Long submitterId, Boolean isGroup, Long assignmentId) {
        SynergyApiController.SynergyGradeRequestDto dto = new SynergyApiController.SynergyGradeRequestDto();
        ReflectionTestUtils.setField(dto, "submitterId", submitterId);
        ReflectionTestUtils.setField(dto, "isGroup", isGroup);
        ReflectionTestUtils.setField(dto, "assignmentId", assignmentId);
        ReflectionTestUtils.setField(dto, "gradeSuggestion", 9.0);
        ReflectionTestUtils.setField(dto, "explanation", "please");
        return dto;
    }
}
