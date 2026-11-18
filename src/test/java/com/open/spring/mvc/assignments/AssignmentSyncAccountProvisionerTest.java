package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.PersonRole;
import com.open.spring.mvc.person.PersonRoleJpaRepository;

@ExtendWith(MockitoExtension.class)
class AssignmentSyncAccountProvisionerTest {
    @Mock PersonRoleJpaRepository roleRepository;
    @Mock PersonJpaRepository personRepository;
    @Mock PersonDetailsService personDetailsService;

    @Test
    void leavesProvisioningDisabledWithoutAnExplicitPassword() {
        AssignmentSyncAccountProvisioner provisioner = provisioner("");

        assertFalse(provisioner.provisionIfConfigured());
        verifyNoInteractions(roleRepository, personRepository, personDetailsService);
    }

    @Test
    void createsADedicatedBotAndSyncRole() {
        PersonRole syncRole = new PersonRole(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC);
        when(personRepository.findByUid("pages-bot")).thenReturn(null);
        when(personRepository.findByEmail("pages-bot@example.com")).thenReturn(null);
        when(roleRepository.findByName(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC)).thenReturn(null);
        when(roleRepository.save(any(PersonRole.class))).thenReturn(syncRole);

        assertTrue(provisioner("test-password").provisionIfConfigured());

        ArgumentCaptor<Person> personCaptor = ArgumentCaptor.forClass(Person.class);
        verify(personDetailsService).save(personCaptor.capture());
        Person bot = personCaptor.getValue();
        assertEquals("pages-bot", bot.getUid());
        assertEquals("pages-bot@example.com", bot.getEmail());
        assertEquals(1, bot.getRoles().size());
        assertTrue(bot.hasRole(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC));
    }

    @Test
    void acceptsAnExistingDedicatedBotWithoutRewritingItsPassword() {
        Person bot = personWithRoles(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC);
        when(personRepository.findByUid("pages-bot")).thenReturn(bot);

        assertTrue(provisioner("test-password").provisionIfConfigured());

        verify(personDetailsService, never()).save(any(Person.class));
        verifyNoInteractions(roleRepository);
    }

    @Test
    void refusesToGrantSyncAuthorityToANormalUser() {
        when(personRepository.findByUid("pages-bot")).thenReturn(personWithRoles("ROLE_STUDENT"));

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> provisioner("test-password").provisionIfConfigured());

        assertTrue(error.getMessage().contains("non-dedicated account"));
        verify(personDetailsService, never()).save(any(Person.class));
    }

    private AssignmentSyncAccountProvisioner provisioner(String password) {
        return new AssignmentSyncAccountProvisioner(
            roleRepository,
            personRepository,
            personDetailsService,
            "pages-bot",
            password,
            "pages-bot@example.com");
    }

    private Person personWithRoles(String... roleNames) {
        Person person = new Person();
        person.setUid("pages-bot");
        person.setRoles(new java.util.ArrayList<>(List.of(roleNames)));
        return person;
    }
}
