package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs the initializer against a real in-memory SQLite database. */
class AssignmentTableInitializerTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE assignment (id INTEGER PRIMARY KEY, name VARCHAR(255))");
        jdbc.update("INSERT INTO assignment (id, name) VALUES (1, 'Pilot')");
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void addsTheClosureColumnsOnceAndLeavesExistingAssignmentsOpen() {
        AssignmentTableInitializer initializer = new AssignmentTableInitializer(jdbc);

        initializer.initialize();
        initializer.initialize();

        assertEquals(List.of("id", "name", "closed_at", "closed_by_id", "team_teach"),
                jdbc.queryForList("SELECT name FROM pragma_table_info('assignment')", String.class));
        assertNull(jdbc.queryForObject("SELECT closed_at FROM assignment WHERE id = 1", String.class));
        assertNull(jdbc.queryForObject("SELECT team_teach FROM assignment WHERE id = 1", String.class));
    }
}
