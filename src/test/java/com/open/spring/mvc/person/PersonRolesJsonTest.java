package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A person's roles are one JSON array of names, and hasRole(name) is the one question the model answers about them.
 */
public class PersonRolesJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Person with(String... roleNames) {
        Person person = new Person();
        person.setRoles(new ArrayList<>(List.of(roleNames)));
        return person;
    }

    // ---- hasRole

    @Test
    public void hasRoleLooksAtTheNamesAndNothingElse() {
        Person person = with("ROLE_USER", "ROLE_MENTOR");
        assertTrue(person.hasRole("ROLE_USER"));
        assertTrue(person.hasRole("ROLE_MENTOR"));
        assertFalse(person.hasRole("ROLE_ADMIN"));
        assertFalse(person.hasRole("ROLE_MENTORS"));
        assertFalse(person.hasRole("role_mentor"));
        assertFalse(person.hasRole(null));
    }

    @Test
    public void aPersonWithNoRolesHasNone() {
        assertFalse(new Person().hasRole("ROLE_USER"));
        Person person = new Person();
        person.setRoles(null);
        assertFalse(person.hasRole("ROLE_USER"));
    }

    @Test
    public void thePeopleWhoCreateAPersonWithRolesGetThem() {
        Person person = Person.createPerson("N", "uid", "e@x.org", "pw", "sid", "pfp", false, List.of("ROLE_USER", "ROLE_STUDENT"));
        assertEquals(List.of("ROLE_USER", "ROLE_STUDENT"), person.getRoles());
        assertTrue(person.hasRole("ROLE_STUDENT"));
        assertEquals("ROLE_TEACHER", new Person("e@x.org", "u", "pw", "sid", "N", null, false, "ROLE_TEACHER").getRoles().get(0));
    }

    // ---- the stored JSON

    @Test
    public void rolesAreStoredAsOneJsonArrayOfNames() {
        RolesJsonConverter converter = new RolesJsonConverter();
        assertEquals("[\"ROLE_USER\",\"ROLE_STUDENT\"]", converter.convertToDatabaseColumn(List.of("ROLE_USER", "ROLE_STUDENT")));
        assertEquals(List.of("ROLE_USER", "ROLE_STUDENT"), converter.convertToEntityAttribute("[\"ROLE_USER\",\"ROLE_STUDENT\"]"));
    }

    @Test
    public void noRolesAreAnEmptyArrayAndAnEmptyColumnIsNoRoles() {
        RolesJsonConverter converter = new RolesJsonConverter();
        assertEquals("[]", converter.convertToDatabaseColumn(new ArrayList<>()));
        assertEquals("[]", converter.convertToDatabaseColumn(null));
        for (String empty : new String[] { null, "", "   ", "[]" }) {
            assertEquals(List.of(), converter.convertToEntityAttribute(empty), String.valueOf(empty));
        }
    }

    @Test
    public void aColumnThatIsNotAJsonArrayOfNamesIsAnErrorNotSilentlyNoRoles() {
        RolesJsonConverter converter = new RolesJsonConverter();
        assertThrows(RuntimeException.class, () -> converter.convertToEntityAttribute("not json"));
        assertThrows(RuntimeException.class, () -> converter.convertToEntityAttribute("{\"name\":\"ROLE_USER\"}"));
    }

    // ---- the JSON people are sent as, and read from

    @Test
    public void aPersonIsStillSentWithEachRoleAsAnObjectWithAName() throws Exception {
        JsonNode roles = MAPPER.readTree(MAPPER.writeValueAsString(with("ROLE_USER", "ROLE_ADMIN"))).get("roles");
        assertTrue(roles.isArray());
        assertEquals(2, roles.size());
        assertEquals("ROLE_USER", roles.get(0).get("name").asText());
        assertEquals("ROLE_ADMIN", roles.get(1).get("name").asText());
    }

    @Test
    public void aPersonCanBeReadWithRolesAsObjectsOrAsPlainNames() throws Exception {
        Person fromObjects = MAPPER.readValue("{\"uid\":\"a\",\"roles\":[{\"id\":1,\"name\":\"ROLE_USER\"},{\"name\":\"ROLE_MENTOR\"}]}", Person.class);
        assertEquals(List.of("ROLE_USER", "ROLE_MENTOR"), fromObjects.getRoles());
        Person fromNames = MAPPER.readValue("{\"uid\":\"b\",\"roles\":[\"ROLE_USER\",\"ROLE_STUDENT\"]}", Person.class);
        assertEquals(List.of("ROLE_USER", "ROLE_STUDENT"), fromNames.getRoles());
    }

    // ---- login

    @Test
    public void loginGivesTheAuthoritiesInTheJson() {
        PersonJpaRepository repository = mock(PersonJpaRepository.class);
        Person person = with("ROLE_USER", "ROLE_STUDENT", "TEACHER", " ", "ROLE_MENTOR");
        person.setUid("someone");
        person.setPassword("pw");
        when(repository.findByUid("someone")).thenReturn(person);
        PersonDetailsService service = new PersonDetailsService();
        ReflectionTestUtils.setField(service, "personJpaRepository", repository);
        UserDetails user = service.loadUserByUsername("someone");
        assertEquals(List.of("ROLE_MENTOR", "ROLE_STUDENT", "ROLE_TEACHER", "ROLE_USER"),
                user.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().collect(Collectors.toList()));
    }

    // ---- finding people by role

    @Test
    public void thePeopleWithARoleAreThoseWhoHaveItInTheirJson() {
        PersonJpaRepository repository = mock(PersonJpaRepository.class, CALLS_REAL_METHODS);
        Person teacher = with("ROLE_USER", "ROLE_TEACHER");
        Person student = with("ROLE_USER", "ROLE_STUDENT");
        when(repository.findAll()).thenReturn(List.of(teacher, student));
        assertEquals(List.of(teacher), repository.findPeopleWithRole("ROLE_TEACHER"));
        assertEquals(List.of(teacher, student), repository.findPeopleWithRole("ROLE_USER"));
        assertEquals(List.of(), repository.findPeopleWithRole("ROLE_ADMIN"));
    }
}
