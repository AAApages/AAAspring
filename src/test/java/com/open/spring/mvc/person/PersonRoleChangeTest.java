package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

/** Giving a person a role, or taking it away, and making the role the first time it is needed. */
public class PersonRoleChangeTest {

    @Mock private PersonJpaRepository personJpaRepository;
    @Mock private PersonRoleJpaRepository personRoleJpaRepository;

    private PersonDetailsService service;
    private final PersonRole userRole = new PersonRole("ROLE_USER");
    private final PersonRole mentorRole = new PersonRole("ROLE_MENTOR");

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        service = new PersonDetailsService();
        ReflectionTestUtils.setField(service, "personJpaRepository", personJpaRepository);
        ReflectionTestUtils.setField(service, "personRoleJpaRepository", personRoleJpaRepository);
        when(personRoleJpaRepository.findByName("ROLE_MENTOR")).thenReturn(mentorRole);
    }

    private Person personWith(PersonRole... roles) {
        Person person = new Person();
        person.setUid("someone");
        person.getRoles().addAll(List.of(roles));
        when(personJpaRepository.findByUid("someone")).thenReturn(person);
        return person;
    }

    private static List<String> names(Person person) {
        return person.getRoles().stream().map(PersonRole::getName).sorted().collect(Collectors.toList());
    }

    @Test
    public void aRoleIsGivenAndTheOthersAreKept() {
        Person person = personWith(userRole);
        assertTrue(service.setRole("someone", "ROLE_MENTOR", true));
        assertEquals(List.of("ROLE_MENTOR", "ROLE_USER"), names(person));
        verify(personJpaRepository).save(person);
    }

    @Test
    public void aRoleIsTakenAwayAndTheOthersAreKept() {
        Person person = personWith(userRole, mentorRole);
        assertTrue(service.setRole("someone", "ROLE_MENTOR", false));
        assertEquals(List.of("ROLE_USER"), names(person));
        verify(personJpaRepository).save(person);
    }

    @Test
    public void givingARoleTheyHaveOrTakingOneTheyDoNotChangesNothing() {
        Person has = personWith(userRole, mentorRole);
        assertTrue(service.setRole("someone", "ROLE_MENTOR", true));
        assertEquals(List.of("ROLE_MENTOR", "ROLE_USER"), names(has));
        Person hasNot = personWith(userRole);
        assertTrue(service.setRole("someone", "ROLE_MENTOR", false));
        assertEquals(List.of("ROLE_USER"), names(hasNot));
        verify(personJpaRepository, never()).save(any(Person.class));
    }

    @Test
    public void aPersonWhoIsNotThereIsReportedAndNothingIsSaved() {
        when(personJpaRepository.findByUid("nobody")).thenReturn(null);
        assertFalse(service.setRole("nobody", "ROLE_MENTOR", true));
        verify(personJpaRepository, never()).save(any(Person.class));
    }

    @Test
    public void theRoleIsFoundWhenItIsThere() {
        assertSame(mentorRole, service.findOrCreateRole("ROLE_MENTOR"));
        verify(personRoleJpaRepository, never()).save(any(PersonRole.class));
    }

    @Test
    public void theRoleIsMadeTheFirstTimeItIsNeeded() {
        when(personRoleJpaRepository.findByName("ROLE_MENTOR")).thenReturn(null);
        when(personRoleJpaRepository.save(any(PersonRole.class))).thenAnswer(call -> call.getArgument(0));
        PersonRole made = service.findOrCreateRole("ROLE_MENTOR");
        assertNotNull(made);
        assertEquals("ROLE_MENTOR", made.getName());
        verify(personRoleJpaRepository).save(any(PersonRole.class));
    }

    @Test
    public void givingARoleThatDoesNotExistYetMakesItFirst() {
        when(personRoleJpaRepository.findByName("ROLE_MENTOR")).thenReturn(null);
        when(personRoleJpaRepository.save(any(PersonRole.class))).thenAnswer(call -> call.getArgument(0));
        Person person = personWith(userRole);
        assertTrue(service.setRole("someone", "ROLE_MENTOR", true));
        assertEquals(List.of("ROLE_MENTOR", "ROLE_USER"), names(person));
    }
}
