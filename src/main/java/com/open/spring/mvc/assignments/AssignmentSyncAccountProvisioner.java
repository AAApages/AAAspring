package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.PersonRole;
import com.open.spring.mvc.person.PersonRoleJpaRepository;

/** Provisions the dedicated account used to synchronize assignment frontmatter. */
@Service
public class AssignmentSyncAccountProvisioner {
    private static final Logger logger = LoggerFactory.getLogger(AssignmentSyncAccountProvisioner.class);

    private final PersonRoleJpaRepository roleRepository;
    private final PersonJpaRepository personRepository;
    private final PersonDetailsService personDetailsService;
    private final String uid;
    private final String password;
    private final String email;

    public AssignmentSyncAccountProvisioner(
            PersonRoleJpaRepository roleRepository,
            PersonJpaRepository personRepository,
            PersonDetailsService personDetailsService,
            @Value("${assignment.sync.uid:pages-bot}") String uid,
            @Value("${assignment.sync.password:}") String password,
            @Value("${assignment.sync.email:pages-bot@example.com}") String email) {
        this.roleRepository = roleRepository;
        this.personRepository = personRepository;
        this.personDetailsService = personDetailsService;
        this.uid = uid == null ? "" : uid.trim();
        this.password = password == null ? "" : password;
        this.email = email == null ? "" : email.trim();
    }

    /**
     * Creates the sync role and account only when a password is explicitly configured.
     * Existing accounts with the configured UID must already be dedicated sync accounts;
     * this prevents silently granting sync authority to a normal user.
     */
    public boolean provisionIfConfigured() {
        if (password.isBlank()) {
            logger.info("Assignment sync account is disabled because PAGES_BOT_PASSWORD is not configured");
            return false;
        }
        if (uid.isBlank() || email.isBlank()) {
            throw new IllegalStateException("Assignment sync UID and email must be non-empty");
        }

        Person existing = personRepository.findByUid(uid);
        if (existing != null) {
            ensureDedicatedSyncAccount(existing);
            return true;
        }

        Person emailOwner = personRepository.findByEmail(email);
        if (emailOwner != null) {
            throw new IllegalStateException("Assignment sync email is already used by another account");
        }

        if (roleRepository.findByName(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC) == null) {
            roleRepository.save(new PersonRole(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC));
        }

        Person bot = Person.createPerson(
            "Assignment Sync Bot",
            uid,
            email,
            password,
            "assignment-sync",
            "/images/default.png",
            false,
            List.of(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC));
        personDetailsService.save(bot);
        logger.info("Provisioned assignment sync account '{}'", uid);
        return true;
    }

    private void ensureDedicatedSyncAccount(Person person) {
        boolean onlySyncRole = person.getRoles() != null
            && person.getRoles().size() == 1
            && person.hasRole(AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC);
        if (!onlySyncRole) {
            throw new IllegalStateException(
                "PAGES_BOT_UID already belongs to a non-dedicated account; choose a separate bot UID");
        }
    }
}
