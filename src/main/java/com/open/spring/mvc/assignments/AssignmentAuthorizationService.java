package com.open.spring.mvc.assignments;

import java.util.List;
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
     * @return true when the user is a teacher, an admin, a creator of this assignment, or a
     *         grader assigned to it (/assignGraders/{id} - a whole-assignment grant, as
     *         opposed to the narrower per-submission one checked by
     *         {@link #canManage(Person, AssignmentSubmission)}).
     */
    public boolean canManage(Person user, Assignment assignment) {
        if (user == null) {
            return false;
        }
        if (isTeacherOrAdmin(user)) {
            return true;
        }
        if (isCreator(user, assignment)) {
            return true;
        }
        return isAssignedGrader(user, assignment);
    }

    /**
     * Distinct name rather than an overload of canManage(Person, Assignment): a bare `null`
     * second argument (as in existing tests) is ambiguous between the two otherwise, since
     * neither Assignment nor AssignmentSubmission is more specific than the other.
     *
     * @return true when {@link #canManage(Person, Assignment)} already allows this user on
     *         the submission's assignment, or they were assigned this specific submission to
     *         grade (/submissions/{id}/assignGraders - e.g. peer grading, where a student
     *         grades one particular classmate's work without managing the whole assignment).
     */
    public boolean canManageSubmission(Person user, AssignmentSubmission submission) {
        if (user == null || submission == null) {
            return false;
        }
        if (canManage(user, submission.getAssignment())) {
            return true;
        }
        List<Person> submissionGraders = submission.getAssignedGraders();
        return submissionGraders != null
            && submissionGraders.stream().anyMatch(grader -> isSamePerson(grader, user));
    }

    /** Whether the user made this submission, alone or as a member of the submitting group. */
    public boolean isSubmitter(Person user, AssignmentSubmission submission) {
        if (user == null || submission == null || submission.getSubmitter() == null) {
            return false;
        }
        return submission.getSubmitter().getMembers().stream().anyMatch(member -> isSamePerson(member, user));
    }

    public boolean isTeacherOrAdmin(Person user) {
        return user != null
            && (user.hasRole(ROLE_TEACHER) || user.hasRole(ROLE_ADMIN));
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
     * Deliberately separate from isCreator: assignedGraders is work assigned via
     * /assignGraders/{id}, not ownership (see the field comment on Assignment.creators).
     */
    public boolean isAssignedGrader(Person user, Assignment assignment) {
        if (user == null || assignment == null) {
            return false;
        }
        List<Person> graders = assignment.getAssignedGraders();
        return graders != null && graders.stream().anyMatch(grader -> isSamePerson(grader, user));
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
        return user != null && user.hasRole(ROLE_ASSIGNMENT_SYNC);
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
