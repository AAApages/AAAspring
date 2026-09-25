package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.admin;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.assignments.AssignmentSubmissionViewController.SubmissionListDTO;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * GET /api/assignment-submission-view/managed scoping.
 */
class AssignmentSubmissionViewControllerManagedTest {

    @Mock
    private AssignmentSubmissionJPA submissionRepo;

    @Mock
    private AssignmentJpaRepository assignmentRepo;

    @Mock
    private PersonJpaRepository personRepo;

    @Mock
    private GroupsJpaRepository groupsRepository;

    private AssignmentSubmissionViewController controller;

    private Person creator;
    private Assignment ownedAssignment;
    private AssignmentSubmission ownedSubmission;
    private AssignmentSubmission foreignSubmission;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        controller = new AssignmentSubmissionViewController();
        ReflectionTestUtils.setField(controller, "submissionRepo", submissionRepo);
        ReflectionTestUtils.setField(controller, "assignmentRepo", assignmentRepo);
        ReflectionTestUtils.setField(controller, "personRepo", personRepo);
        ReflectionTestUtils.setField(controller, "assignmentAuthorizationService", new AssignmentAuthorizationService());
        CourseGroupProperties courseProperties = new CourseGroupProperties();
        courseProperties.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
        ReflectionTestUtils.setField(controller, "courseGroupProperties", courseProperties);
        ReflectionTestUtils.setField(controller, "groupsRepository", groupsRepository);
        ReflectionTestUtils.setField(controller, "assignmentCourseSyncService",
            new AssignmentCourseSyncService(groupsRepository, courseProperties));

        creator = student(1L, "AdityaS-2010");
        ownedAssignment = assignment(10L, "pilot", creator);
        ownedSubmission = submission(100L, ownedAssignment);
        foreignSubmission = submission(200L, assignment(11L, "not-mine"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Person person, String... authorities) {
        when(personRepo.findByUid(person.getUid())).thenReturn(person);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            person.getUid(), "ignored", AuthorityUtils.createAuthorityList(authorities)));
    }

    @SuppressWarnings("unchecked")
    private List<SubmissionListDTO> dtos(ResponseEntity<?> response) {
        return (List<SubmissionListDTO>) response.getBody();
    }

    @Test
    void creatorSeesOnlySubmissionsFromOwnedAssignments() {
        authenticateAs(creator, "ROLE_STUDENT");
        when(assignmentRepo.findByCreatorId(1L)).thenReturn(List.of(ownedAssignment));
        when(submissionRepo.findByAssignmentIdIn(List.of(10L))).thenReturn(List.of(ownedSubmission));

        ResponseEntity<?> response = controller.getManagedSubmissions();

        assertEquals(200, response.getStatusCode().value());
        List<SubmissionListDTO> body = dtos(response);
        assertEquals(1, body.size());
        assertEquals(100L, body.get(0).getId());
        assertEquals(10L, body.get(0).getAssignmentId());
        verify(submissionRepo, never()).findAll();
    }

    @Test
    void managedSubmissionIncludesAssignmentAndStudentCourseMetadata() {
        ownedSubmission.setSubmitter(creator);
        ownedAssignment.setContentUrl("csa/pilot");
        Groups csa = new Groups();
        csa.setName("CSA");
        ownedAssignment.getCourseGroups().add(csa);
        authenticateAs(creator, "ROLE_STUDENT");
        when(assignmentRepo.findByCreatorId(1L)).thenReturn(List.of(ownedAssignment));
        when(submissionRepo.findByAssignmentIdIn(List.of(10L))).thenReturn(List.of(ownedSubmission));
        when(groupsRepository.findCourseMembershipsByPersonIds(any(), any()))
            .thenReturn(java.util.Collections.singletonList(new Object[] {1L, "CSA"}));

        SubmissionListDTO dto = dtos(controller.getManagedSubmissions()).get(0);

        assertEquals("AdityaS-2010", dto.getSubmitterUid());
        assertEquals(List.of("CSA"), dto.getSubmitterCourseCodes());
        assertEquals("csa/pilot", dto.getAssignmentContentUrl());
        assertEquals(List.of("CSA"), dto.getAssignmentCourseCodes());
    }

    @Test
    void studentWithTwoMembershipsIncludesBothCourses() {
        ownedSubmission.setSubmitter(creator);
        authenticateAs(creator, "ROLE_STUDENT");
        when(assignmentRepo.findByCreatorId(1L)).thenReturn(List.of(ownedAssignment));
        when(submissionRepo.findByAssignmentIdIn(List.of(10L))).thenReturn(List.of(ownedSubmission));
        when(groupsRepository.findCourseMembershipsByPersonIds(any(), any()))
            .thenReturn(List.of(new Object[] {1L, "CSP"}, new Object[] {1L, "CSA"}));

        SubmissionListDTO dto = dtos(controller.getManagedSubmissions()).get(0);

        assertEquals(List.of("CSA", "CSP"), dto.getSubmitterCourseCodes());
        verify(groupsRepository).findCourseMembershipsByPersonIds(any(), any());
    }

    @Test
    void canonicalGroupSubmissionUsesItsCourseWithoutAStudentMembershipQuery() {
        Groups classGroup = new Groups();
        classGroup.setName("CSA");
        ownedSubmission.setSubmitter(classGroup);
        authenticateAs(creator, "ROLE_STUDENT");
        when(assignmentRepo.findByCreatorId(1L)).thenReturn(List.of(ownedAssignment));
        when(submissionRepo.findByAssignmentIdIn(List.of(10L))).thenReturn(List.of(ownedSubmission));

        SubmissionListDTO dto = dtos(controller.getManagedSubmissions()).get(0);

        assertEquals(List.of("CSA"), dto.getSubmitterCourseCodes());
        assertTrue(dto.getIsGroup());
        verify(groupsRepository, never()).findCourseMembershipsByPersonIds(any(), any());
    }

    @Test
    void studentWithoutOwnedAssignmentsGetsAnEmptyList() {
        authenticateAs(student(2L, "regular-student"), "ROLE_STUDENT");
        when(assignmentRepo.findByCreatorId(2L)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getManagedSubmissions();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(dtos(response).isEmpty());
        verify(submissionRepo, never()).findAll();
        verify(submissionRepo, never()).findByAssignmentIdIn(any());
    }

    @Test
    void staffSeeEverySubmission() {
        authenticateAs(admin(4L, "toby"), "ROLE_ADMIN");
        when(submissionRepo.findAll()).thenReturn(List.of(ownedSubmission, foreignSubmission));

        ResponseEntity<?> response = controller.getManagedSubmissions();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, dtos(response).size());
        verify(assignmentRepo, never()).findByCreatorId(any());
    }

    @Test
    void unauthenticatedManagedRequestsAreRejected() {
        SecurityContextHolder.clearContext();

        ResponseEntity<?> response = controller.getManagedSubmissions();

        assertEquals(401, response.getStatusCode().value());
    }
}
