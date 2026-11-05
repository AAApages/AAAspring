package com.open.spring.mvc.sprintretro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.sprintretro.SprintRetroService.CalendarSprint;
import com.open.spring.mvc.sprintretro.SprintRetroService.InvalidRetroException;
import com.open.spring.mvc.sprintretro.SprintRetroService.Progress;
import com.open.spring.mvc.sprintretro.SprintRetroService.ReflectionView;
import com.open.spring.mvc.sprintretro.SprintRetroService.RosterRow;
import com.open.spring.mvc.sprintretro.SprintRetroService.SprintInUseException;
import com.open.spring.mvc.sprintretro.SprintRetroService.SprintView;
import com.open.spring.mvc.sprintretro.SprintRetroService.StudentPart;
import com.open.spring.mvc.sprintretro.SprintRetroService.TeacherPart;

class SprintRetroServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 10, 0);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    @Mock
    private SprintRetroJpaRepository sprintRepository;

    @Mock
    private SprintReflectionJpaRepository reflectionRepository;

    @Mock
    private PersonJpaRepository personRepository;

    private SprintRetroService service;
    private Person student;
    private Person teacher;
    private SprintRetroEntity sprint1;
    private SprintRetroEntity sprint2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new SprintRetroService(sprintRepository, reflectionRepository, personRepository, new ObjectMapper(), clock);
        student = person(1L, "Hop", "hop");
        teacher = person(2L, "Niko", "niko");
        sprint1 = sprint(10L, "Sprint 1", SEP_1);
        sprint2 = sprint(11L, "Sprint 2", SEP_1.plusWeeks(4));
        when(sprintRepository.findById(10L)).thenReturn(Optional.of(sprint1));
        when(sprintRepository.findById(11L)).thenReturn(Optional.of(sprint2));
        when(sprintRepository.findAllByOrderByStartDateAscIdAsc()).thenReturn(List.of(sprint1, sprint2));
        when(sprintRepository.save(any(SprintRetroEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reflectionRepository.save(any(SprintReflectionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reflectionRepository.findBySprintAndPerson(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void templateIdsAreUniqueAndMatchGround0() {
        List<String> items = SprintRetroTemplate.itemIds();
        assertEquals(22, items.size());
        assertEquals(items.size(), new HashSet<>(items).size());
        assertEquals("T01", items.get(0));
        assertEquals("P10", items.get(items.size() - 1));
        assertEquals("AI Orchestration", SprintRetroTemplate.ITEMS.get("T09").area());
        assertEquals(16, SprintRetroTemplate.TEXT_KEYS.size()); // 6 section summaries + 10 questions
    }

    @Test
    void createSprintTrimsTheNameAndRecordsWhoMadeIt() {
        SprintView view = service.createSprint(teacher, "  Sprint 3 ", SEP_1, SEP_1.plusDays(27));

        assertEquals("Sprint 3", view.name());
        assertEquals(SEP_1, view.startDate());
        verify(sprintRepository).save(any(SprintRetroEntity.class));
    }

    @Test
    void sprintRulesAreChecked() {
        assertInvalid(() -> service.createSprint(teacher, " ", SEP_1, null), "give the sprint a name");
        assertInvalid(() -> service.createSprint(teacher, "Sprint", null, null), "pick a start date");
        assertInvalid(() -> service.createSprint(teacher, "Sprint", SEP_1, SEP_1.minusDays(1)),
                "the end date must be on or after the start date");
        assertInvalid(() -> service.createSprint(teacher, "x".repeat(101), SEP_1, null), "keep the name to 100 characters");
        verify(sprintRepository, never()).save(any());
    }

    @Test
    void calendarSyncAddsNewSprintsAndMovesDatesWithoutTouchingTheRest() {
        LocalDate moved = SEP_1.plusDays(1);
        LocalDate secondStart = sprint2.getStartDate();
        LocalDate secondEnd = secondStart.plusDays(25);
        sprint2.setEndDate(secondEnd);

        service.syncCalendar(teacher, List.of(
                new CalendarSprint("Sprint 1", moved, moved.plusDays(25)),
                new CalendarSprint("Sprint 2", secondStart, secondEnd),
                new CalendarSprint("Sprint 3", secondEnd.plusDays(3), secondEnd.plusDays(28))));

        assertEquals(moved, sprint1.getStartDate());
        assertEquals(moved.plusDays(25), sprint1.getEndDate());
        verify(sprintRepository).save(sprint1);
        verify(sprintRepository, never()).save(sprint2);
        ArgumentCaptor<SprintRetroEntity> saved = ArgumentCaptor.forClass(SprintRetroEntity.class);
        verify(sprintRepository, times(2)).save(saved.capture());
        assertEquals("Sprint 3", saved.getAllValues().get(1).getName());
        assertEquals(teacher, saved.getAllValues().get(1).getCreatedBy());
        verify(sprintRepository, never()).delete(any());
    }

    @Test
    void calendarSyncRejectsAnEmptyOrInvalidCalendar() {
        assertInvalid(() -> service.syncCalendar(teacher, List.of()), "the calendar lists no sprints");
        assertInvalid(() -> service.syncCalendar(teacher, List.of(new CalendarSprint("Sprint 3", null, null))), "pick a start date");
    }

    @Test
    void checklistIdsFromAnyPageAreAcceptedAndKept() {
        ReflectionView view = service.saveStudentPart(10L, student, new StudentPart(null, null, List.of("g1-1", "c-1a2b3c"))).orElseThrow();

        assertEquals(List.of("g1-1", "c-1a2b3c"), view.student().checks());
    }

    @Test
    void aSprintWithReflectionsIsNotDeleted() {
        when(reflectionRepository.existsBySprint(sprint1)).thenReturn(true);
        assertThrows(SprintInUseException.class, () -> service.deleteSprint(10L));
        verify(sprintRepository, never()).delete(any());

        assertTrue(service.deleteSprint(11L));
        verify(sprintRepository).delete(sprint2);
        when(sprintRepository.findById(99L)).thenReturn(Optional.empty());
        assertFalse(service.deleteSprint(99L));
    }

    @Test
    void aReflectionNotStartedIsBlank() {
        ReflectionView view = service.reflection(10L, student).orElseThrow();

        assertEquals("Sprint 1", view.sprint().name());
        assertTrue(view.student().ranks().isEmpty());
        assertTrue(view.teacher().ranks().isEmpty());
        assertNull(view.updatedAt());
        when(sprintRepository.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.reflection(99L, student).isEmpty());
    }

    @Test
    void savingTheStudentPartCleansItAndKeepsTheTeachersPart() {
        SprintReflectionEntity stored = new SprintReflectionEntity();
        stored.setSprint(sprint1);
        stored.setPerson(student);
        stored.setTeacherData("{\"ranks\":{\"T01\":3},\"comments\":{\"T01\":\"Good setup notes\"},\"overall\":\"Nice sprint\"}");
        when(reflectionRepository.findBySprintAndPerson(sprint1, student)).thenReturn(Optional.of(stored));
        Map<String, Integer> ranks = new HashMap<>();
        ranks.put("T01", 4);
        ranks.put("T02", 0);
        ranks.put("P04", null);
        Map<String, String> text = Map.of("tools", "  Fixed my Ruby path ", "techGoal", " ");

        ReflectionView view = service.saveStudentPart(10L, student, new StudentPart(ranks, text, List.of("g1-1", "g1-1"))).orElseThrow();

        assertEquals(Map.of("T01", 4), view.student().ranks());
        assertEquals(Map.of("tools", "Fixed my Ruby path"), view.student().text());
        assertEquals(List.of("g1-1"), view.student().checks());
        assertEquals(NOW, view.updatedAt());
        assertEquals(3, view.teacher().ranks().get("T01"));
        assertEquals("Nice sprint", view.teacher().overall());
    }

    @Test
    void studentPartRulesAreChecked() {
        assertInvalid(() -> service.saveStudentPart(10L, student, new StudentPart(Map.of("T99", 3), null, null)), "unknown item T99");
        assertInvalid(() -> service.saveStudentPart(10L, student, new StudentPart(Map.of("T01", 6), null, null)),
                "T01 needs a rank from 0 to 5");
        assertInvalid(() -> service.saveStudentPart(10L, student, new StudentPart(null, Map.of("mood", "ok"), null)),
                "unknown question mood");
        assertInvalid(() -> service.saveStudentPart(10L, student, new StudentPart(null, Map.of("tools", "x".repeat(2001)), null)),
                "keep each answer to 2000 characters");
        assertInvalid(() -> service.saveStudentPart(10L, student, new StudentPart(null, null, List.of("Not An Id"))),
                "invalid checklist item Not An Id");
        verify(reflectionRepository, never()).save(any());
    }

    @Test
    void savingFeedbackRecordsTheTeacher() {
        ReflectionView view = service.saveTeacherPart(10L, student, teacher,
                new TeacherPart(Map.of("T03", 2), Map.of("T03", " Commit more often ", "tools", "Great detail"), " Keep going ")).orElseThrow();

        assertEquals(Map.of("T03", 2), view.teacher().ranks());
        assertEquals("Commit more often", view.teacher().comments().get("T03"));
        assertEquals("Keep going", view.teacher().overall());
        assertEquals(NOW, view.teacherUpdatedAt());
        assertEquals("Niko", view.teacherName());
        assertTrue(view.student().ranks().isEmpty());
        assertInvalid(() -> service.saveTeacherPart(10L, student, teacher, new TeacherPart(null, Map.of("nope", "x"), null)),
                "unknown item nope");
    }

    @Test
    void theRosterListsSubmittedStudentsFirst() {
        Person other = person(3L, "Ava", "ava");
        SprintReflectionEntity submitted = reflection(sprint1, student, "{\"ranks\":{\"T01\":2,\"T02\":3}}", null);
        when(reflectionRepository.findBySprint(sprint1)).thenReturn(List.of(submitted));
        when(personRepository.findPeopleWithRole("ROLE_STUDENT")).thenReturn(List.of(other, student));

        List<RosterRow> roster = service.roster(10L).orElseThrow();

        assertEquals(List.of("Hop", "Ava"), roster.stream().map(RosterRow::name).toList());
        assertEquals(2, roster.get(0).ranked());
        assertTrue(roster.get(0).submitted());
        assertFalse(roster.get(0).reviewed());
        assertFalse(roster.get(1).submitted());
    }

    @Test
    void progressLinesUpRanksWithSprints() {
        SprintReflectionEntity second = reflection(sprint2, student,
                "{\"ranks\":{\"T01\":4},\"text\":{\"techRetro\":\"I can debug CI\"}}",
                "{\"ranks\":{\"T01\":3},\"overall\":\"Strong growth\"}");
        when(reflectionRepository.findByPerson(student)).thenReturn(List.of(second));

        Progress progress = service.progress(student);

        assertEquals(List.of("Sprint 1", "Sprint 2"), progress.sprints().stream().map(SprintView::name).toList());
        assertEquals(22, progress.items().size());
        assertEquals("Tools & Development Environment", progress.items().get(0).section());
        assertEquals(Arrays.asList(null, 4), progress.items().get(0).self());
        assertEquals(Arrays.asList(null, 3), progress.items().get(0).teacher());
        assertEquals("I can debug CI", progress.text().get(1).get("techRetro"));
        assertEquals(Arrays.asList(null, "Strong growth"), progress.teacherOverall());
    }

    private static void assertInvalid(Runnable call, String message) {
        InvalidRetroException e = assertThrows(InvalidRetroException.class, call::run);
        assertEquals(message, e.getMessage());
    }

    private static Person person(Long id, String name, String uid) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setName(name);
        person.setUid(uid);
        return person;
    }

    private static SprintRetroEntity sprint(Long id, String name, LocalDate start) {
        SprintRetroEntity sprint = new SprintRetroEntity();
        sprint.setId(id);
        sprint.setName(name);
        sprint.setStartDate(start);
        sprint.setCreatedAt(NOW);
        return sprint;
    }

    private static SprintReflectionEntity reflection(SprintRetroEntity sprint, Person person, String studentData, String teacherData) {
        SprintReflectionEntity reflection = new SprintReflectionEntity();
        reflection.setSprint(sprint);
        reflection.setPerson(person);
        reflection.setStudentData(studentData);
        reflection.setTeacherData(teacherData);
        reflection.setUpdatedAt(NOW);
        return reflection;
    }
}
