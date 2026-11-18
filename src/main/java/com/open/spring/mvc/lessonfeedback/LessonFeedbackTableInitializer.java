package com.open.spring.mvc.lessonfeedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Initializes the lesson_feedback table for databases managed with ddl-auto=none.
 *
 * It only creates the table when it is missing; an existing table and its rows are never
 * changed. The columns match the names Hibernate maps LessonFeedbackEntity's fields to.
 */
@Component
@RequiredArgsConstructor
public class LessonFeedbackTableInitializer {

    static final String TABLE = "lesson_feedback";

    private static final Logger logger = LoggerFactory.getLogger(LessonFeedbackTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateLessonFeedback() {
        return args -> initialize();
    }

    void initialize() {
        try {
            // Unquoted lowercase names and plain types so the same statement runs on SQLite and MySQL.
            // The id is a UUID set in Java, as for help requests, so no database-specific auto id is needed.
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id varchar(36) NOT NULL PRIMARY KEY, "
                    + "version bigint NOT NULL DEFAULT 0, "
                    + "assignment_id bigint NOT NULL, "
                    + "uid varchar(255) NOT NULL, "
                    + "lesson_rating integer, "
                    + "engage_rating integer, "
                    + "popcorn_rating integer, "
                    + "homework_rating integer, "
                    + "pros text, "
                    + "grows text, "
                    + "created_at bigint NOT NULL, "
                    + "updated_at bigint NOT NULL, "
                    + "CONSTRAINT uk_lesson_feedback_assignment_uid UNIQUE (assignment_id, uid))");
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", TABLE, e.getMessage());
        }
    }
}
