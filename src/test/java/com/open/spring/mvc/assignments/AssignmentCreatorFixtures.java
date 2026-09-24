package com.open.spring.mvc.assignments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonRole;

/**
 * Shared builders for the creator-permission tests.
 *
 * Person ids live on the Submitter superclass and have no setter, so they are set
 * reflectively; the tests need stable ids because creator matching compares on id.
 */
final class AssignmentCreatorFixtures {

    private AssignmentCreatorFixtures() {
    }

    static Person person(Long id, String uid, String... roleNames) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        List<PersonRole> roles = new ArrayList<>();
        for (String roleName : roleNames) {
            roles.add(new PersonRole(roleName));
        }
        person.setRoles(roles);
        return person;
    }

    static Person student(Long id, String uid) {
        return person(id, uid, "ROLE_USER", "ROLE_STUDENT");
    }

    static Person teacher(Long id, String uid) {
        return person(id, uid, "ROLE_USER", "ROLE_TEACHER");
    }

    static Person admin(Long id, String uid) {
        return person(id, uid, "ROLE_USER", "ROLE_ADMIN");
    }

    static Person syncBot(Long id, String uid) {
        return person(id, uid, AssignmentAuthorizationService.ROLE_ASSIGNMENT_SYNC);
    }

    static Assignment assignment(Long id, String name, Person... creators) {
        Assignment assignment = new Assignment(name, "auto-created", "[CONTENT_URL: csa/" + name + "/]", 1.0, "10/25/2026", "file");
        assignment.setId(id);
        assignment.getCreators().addAll(Arrays.asList(creators));
        return assignment;
    }

    static AssignmentSubmission submission(Long id, Assignment assignment) {
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setId(id);
        submission.setAssignment(assignment);
        return submission;
    }
}
