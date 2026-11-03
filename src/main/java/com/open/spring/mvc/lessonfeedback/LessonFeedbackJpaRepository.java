package com.open.spring.mvc.lessonfeedback;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LessonFeedbackJpaRepository extends JpaRepository<LessonFeedbackEntity, String> {
    /** A student has at most one row per assignment (uk_lesson_feedback_assignment_uid). */
    Optional<LessonFeedbackEntity> findByAssignmentIdAndUid(Long assignmentId, String uid);

    List<LessonFeedbackEntity> findByAssignmentId(Long assignmentId);
}
