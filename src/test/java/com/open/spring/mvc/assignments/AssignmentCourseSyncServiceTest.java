package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;

class AssignmentCourseSyncServiceTest {

    @Mock
    private GroupsJpaRepository groupsRepository;

    private AssignmentCourseSyncService service;
    private Groups csa;
    private Groups csp;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        CourseGroupProperties properties = new CourseGroupProperties();
        properties.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
        service = new AssignmentCourseSyncService(groupsRepository, properties);
        csa = new Groups();
        csa.setName("CSA");
        csp = new Groups();
        csp.setName("CSP");
        when(groupsRepository.findByName("CSA")).thenReturn(Optional.of(csa));
        when(groupsRepository.findByName("CSP")).thenReturn(Optional.of(csp));
    }

    @Test
    void normalizesAndDeduplicatesCourseCodes() {
        assertEquals(List.of("CSA", "CSP"),
            service.normalizeCourseCodes(List.of(" csa ", "CSP", "CSA")));
    }

    @Test
    void rejectsExplicitlyEmptyOrBlankCourseLists() {
        assertThrows(IllegalArgumentException.class,
            () -> service.normalizeCourseCodes(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> service.normalizeCourseCodes(List.of("CSA", " ")));
    }

    @Test
    void appliesMultipleCanonicalCourseGroupsIdempotently() {
        Assignment assignment = AssignmentCreatorFixtures.assignment(1L, "ground-zero");
        List<Groups> resolved = service.resolveCourseGroups(List.of("CSA", "CSP"));

        assertTrue(service.applyCourseGroups(assignment, resolved));
        assertEquals(List.of("CSA", "CSP"), service.courseCodesOf(assignment));
        assertFalse(service.applyCourseGroups(assignment, resolved));
    }

    @Test
    void rejectsUnknownCoursesBeforeChangingTheAssignment() {
        Assignment assignment = AssignmentCreatorFixtures.assignment(1L, "ground-zero");
        assignment.getCourseGroups().add(csa);

        assertThrows(AssignmentCourseSyncService.UnknownCourseException.class,
            () -> service.resolveCourseGroups(List.of("CSA", "UNKNOWN")));
        assertEquals(List.of("CSA"), service.courseCodesOf(assignment));
    }
}
