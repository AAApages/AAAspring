package com.open.spring.mvc.push;

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
class PushSubscriptionTableInitializerTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private PushSubscriptionTableInitializer initializer;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE person (id INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO person (id) VALUES (1)");
        initializer = new PushSubscriptionTableInitializer(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    private void insert(String endpoint) {
        jdbc.update("INSERT INTO push_subscription (person_id, endpoint, p256dh, auth) VALUES (1, ?, 'k', 'a')", endpoint);
    }

    @Test
    void createsTheTableWithEveryEntityColumn() {
        initializer.initialize();

        assertEquals(List.of("id", "person_id", "endpoint", "p256dh", "auth", "user_agent", "created_at", "last_used_at"),
                jdbc.queryForList("SELECT name FROM pragma_table_info('push_subscription')", String.class));
    }

    @Test
    void runningAgainKeepsExistingRows() {
        initializer.initialize();
        insert("https://push.example/a");

        initializer.initialize();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM push_subscription", Integer.class));
    }

    @Test
    void oneRowPerBrowserEndpoint() {
        initializer.initialize();
        insert("https://push.example/a");

        assertThrows(DataAccessException.class, () -> insert("https://push.example/a"));
    }
}
