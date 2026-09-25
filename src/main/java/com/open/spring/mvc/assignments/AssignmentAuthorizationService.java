package com.open.spring.mvc.assignments;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;

/**
 * The single place that answers "may this person manage this assignment?".
 *
 * Assignment management is deliberately narrow in this release: it covers a specific
 * assignment's submissions (viewing, grading, AI summaries, deletion) and nothing else.
 * Creators get no global privileges and no rights over assignments they do not own.
 */
@Service
public class AssignmentAuthorizationService {

    /** Role held only by the trusted Pages synchronization bot; never granted to students. */
    public static final String ROLE_ASSIGNMENT_SYNC = "ROLE_ASSIGNMENT_SYNC";

    public static final String ROLE_TEACHER = "ROLE_TEACHER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * @return true when the user is a teacher, an admin, or a creator of this assignment.
     */
    public boolean canManage(Person user, Assignment assignment) {
        if (user == null) {
            return false;
        }
        if (isTeacherOrAdmin(user)) {
            return true;
        }
        return isCreator(user, assignment);
    }

    public boolean isTeacherOrAdmin(Person user) {
        return user != null
            && (user.hasRoleWithName(ROLE_TEACHER) || user.hasRoleWithName(ROLE_ADMIN));
    }

    /**
     * Creator membership is compared by database id (falling back to uid) because Person
     * inherits identity equality from Submitter, so two Hibernate instances of the same
     * account are never equal to each other.
     */
    public boolean isCreator(Person user, Assignment assignment) {
        if (user == null || assignment == null) {
            return false;
        }
        return assignment.getCreators().stream().anyMatch(creator -> isSamePerson(creator, user));
    }

    /**
     * Only the trusted sync account may set or replace assignment ownership. Teachers and
     * admins are intentionally excluded: Pages frontmatter stays the source of ownership.
     */
    public boolean canSynchronizeCreators(Person user) {
        return canSynchronizeAssignmentMetadata(user);
    }

    /** Course and creator frontmatter share the same trusted synchronization boundary. */
    public boolean canSynchronizeAssignmentMetadata(Person user) {
        return user != null && user.hasRoleWithName(ROLE_ASSIGNMENT_SYNC);
    }

    private boolean isSamePerson(Person left, Person right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && right.getId() != null) {
            return Objects.equals(left.getId(), right.getId());
        }
        return left.getUid() != null && Objects.equals(left.getUid(), right.getUid());
    }
}
