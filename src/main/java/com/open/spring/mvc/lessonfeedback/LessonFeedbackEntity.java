package com.open.spring.mvc.lessonfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.open.spring.mvc.lessonfeedback.LessonFeedbackRules.Feedback;

/**
 * One student's feedback on the lesson behind one assignment: one row of lesson_feedback.
 *
 * It follows the help-request shape (see ToolchainHelpEntity): a random UUID id, the
 * sender's uid taken from the login, where it was sent from (the assignment, whose
 * contentUrl is the lesson page), and server times in epoch milliseconds. A null rating
 * or text has not been given yet. Changed only through LessonFeedbackService; the table
 * is initialized by LessonFeedbackTableInitializer (ddl-auto is none).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "lesson_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_lesson_feedback_assignment_uid",
                columnNames = {"assignment_id", "uid"}))
public class LessonFeedbackEntity {

    @Id
    private String id;

    // Two saves racing from different tabs must not both pass the locked-rating check.
    @Version
    private Long version;

    @Column(nullable = false)
    private Long assignmentId;

    @Column(nullable = false)
    private String uid;

    private Integer lessonRating;

    private Integer engageRating;

    private Integer popcornRating;

    private Integer homeworkRating;

    @Column(columnDefinition = "text")
    private String pros;

    @Column(columnDefinition = "text")
    private String grows;

    @Column(nullable = false)
    private Long createdAt;

    @Column(nullable = false)
    private Long updatedAt;

    /** The saved values in the form LessonFeedbackRules works with. */
    public Feedback toFeedback() {
        return new Feedback(lessonRating, engageRating, popcornRating, homeworkRating, pros, grows);
    }

    /** Stores values that LessonFeedbackRules has already merged and checked. */
    public void apply(Feedback feedback) {
        lessonRating = feedback.lesson();
        engageRating = feedback.engage();
        popcornRating = feedback.popcorn();
        homeworkRating = feedback.homework();
        pros = feedback.pros();
        grows = feedback.grows();
    }
}
