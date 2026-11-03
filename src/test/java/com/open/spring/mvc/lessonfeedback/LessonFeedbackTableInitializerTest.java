package com.open.spring.mvc.lessonfeedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the initializer against a real in-memory SQLite database. */
class LessonFeedbackTableInitializerTest {

    private static final List<String> ALL_COLUMNS = List.of(
            "id", "version", "assignment_id", "uid", "lesson_rating", "engage_rating", "popcorn_rating",
            "homework_rating", "pros", "grows", "created_at", "updated_at");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private LessonFeedbackTableInitializer initializer;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        initializer = new LessonFeedbackTableInitializer(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private void insert(String id, long assignmentId, String uid) {
        jdbc.update("INSERT INTO lesson_feedback (id, assignment_id, uid, created_at, updated_at) VALUES (?, ?, ?, 1, 1)",
                id, assignmentId, uid);
    }

    @Test
    void createsTheTableWithEveryEntityColumn() {
        initializer.initialize();

        assertEquals(ALL_COLUMNS,
                jdbc.queryForList("SELECT name FROM pragma_table_info('lesson_feedback')", String.class));
    }

    @Test
    void runningAgainKeepsExistingRows() {
        initializer.initialize();
        insert("a", 10L, "hop");

        initializer.initialize();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lesson_feedback", Integer.class));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM lesson_feedback WHERE id = 'a'", Long.class));
    }

    @Test
    void aStudentHasOneRowPerAssignment() {
        initializer.initialize();
        insert("a", 10L, "hop");
        insert("b", 11L, "hop");
        insert("c", 10L, "lex");

        // Plain JDBC on SQLite may not classify the error; Hibernate reports it as DataIntegrityViolationException.
        assertThrows(DataAccessException.class, () -> insert("d", 10L, "hop"));
    }
}
