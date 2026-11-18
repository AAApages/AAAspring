package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The assignment migrations on a real (in-memory) SQLite, and the SQL they send.
 *
 * Their statements must not quote names with double quotes: MySQL reads a double-quoted name as a string unless
 * ANSI_QUOTES is on, and every failure in a migration is swallowed, so on MySQL a quoted statement silently does
 * nothing. SQLite accepts either form, so only looking at the SQL itself can catch it.
 */
public class AssignmentMigrationsSqlTest {

    private static final String URL = "https://example.org/lessons/loops";

    /** Hands out one real connection and writes down every SQL string sent through it. */
    private static final class RecordingDataSource extends AbstractDataSource {
        final List<String> sql = new ArrayList<>();
        private final Connection real;

        RecordingDataSource() throws SQLException {
            real = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                    return null;  // one connection serves the whole test
                }
                Object result = call(real, method, args);
                if (method.getName().equals("prepareStatement")) {
                    sql.add((String) args[0]);
                }
                if (method.getName().equals("createStatement") || method.getName().equals("prepareStatement")) {
                    return recording(result);
                }
                return result;
            });
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        private Object recording(Object statement) {
            return Proxy.newProxyInstance(getClass().getClassLoader(), statement.getClass().getInterfaces(), (proxy, method, args) -> {
                if (args != null && args.length > 0 && args[0] instanceof String
                        && Set.of("execute", "executeQuery", "executeUpdate").contains(method.getName())) {
                    sql.add((String) args[0]);
                }
                return call(statement, method, args);
            });
        }

        private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        void shutdown() throws SQLException {
            real.close();
        }
    }

    private RecordingDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    public void database() throws Exception {
        dataSource = new RecordingDataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE person (id INTEGER PRIMARY KEY, uid TEXT)");
        jdbc.execute("CREATE TABLE groups (id INTEGER PRIMARY KEY, name TEXT)");
        jdbc.execute("CREATE TABLE assignment (id INTEGER PRIMARY KEY, description TEXT)");
        dataSource.sql.clear();  // only what the migrations send is looked at
    }

    @AfterEach
    public void close() throws Exception {
        dataSource.shutdown();
    }

    private void runAll() throws Exception {
        new AssignmentContentUrlMigration(jdbc).migrateAssignmentContentUrls().run(null);
        new AssignmentCourseGroupsMigration(jdbc).migrateAssignmentCourseGroups().run(null);
        new AssignmentCreatorsMigration(jdbc).migrateAssignmentCreators().run(null);
    }

    private boolean has(String objectType, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?", Integer.class, objectType, name) > 0;
    }

    @Test
    public void noStatementQuotesANameWithDoubleQuotes() throws Exception {
        jdbc.update("INSERT INTO assignment (id, description) VALUES (1, ?)", "[CONTENT_URL: " + URL + "] Loops");
        runAll();
        runAll();  // a second boot sends a different set of statements
        assertFalse(dataSource.sql.isEmpty());
        for (String statement : dataSource.sql) {
            assertFalse(statement.contains("\""), "double-quoted name in: " + statement);
        }
    }

    @Test
    public void theContentUrlColumnIsAddedAndFilledFromTheLegacyMarker() throws Exception {
        jdbc.update("INSERT INTO assignment (id, description) VALUES (1, ?)", "[CONTENT_URL: " + URL + "] Loops");
        jdbc.update("INSERT INTO assignment (id, description) VALUES (2, ?)", "Plain description, no marker");
        runAll();
        assertEquals(AssignmentContentUrls.canonicalize(URL), jdbc.queryForObject("SELECT content_url FROM assignment WHERE id = 1", String.class));
        assertEquals(null, jdbc.queryForObject("SELECT content_url FROM assignment WHERE id = 2", String.class));
        assertEquals("[CONTENT_URL: " + URL + "] Loops", jdbc.queryForObject("SELECT description FROM assignment WHERE id = 1", String.class));
    }

    @Test
    public void contentUrlIsMadeUniqueByAnIndex() throws Exception {
        runAll();
        assertTrue(has("index", "idx_assignment_content_url"));
        jdbc.update("INSERT INTO assignment (id, description, content_url) VALUES (1, 'a', 'u')");
        Exception refused = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("INSERT INTO assignment (id, description, content_url) VALUES (2, 'b', 'u')"));
        assertTrue(refused.getMessage().contains("UNIQUE constraint failed"), refused.getMessage());
    }

    @Test
    public void duplicatesAlreadyThereKeepTheIndexOffInsteadOfFailing() throws Exception {
        jdbc.execute("ALTER TABLE assignment ADD COLUMN content_url varchar(255)");
        jdbc.update("INSERT INTO assignment (id, description, content_url) VALUES (1, 'a', 'same'), (2, 'b', 'same')");
        runAll();
        assertFalse(has("index", "idx_assignment_content_url"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM assignment", Integer.class));
    }

    @Test
    public void runningTheMigrationsAgainChangesNothing() throws Exception {
        jdbc.update("INSERT INTO assignment (id, description) VALUES (1, ?)", "[CONTENT_URL: " + URL + "] Loops");
        runAll();
        String before = jdbc.queryForObject("SELECT content_url FROM assignment WHERE id = 1", String.class);
        runAll();
        assertEquals(before, jdbc.queryForObject("SELECT content_url FROM assignment WHERE id = 1", String.class));
        assertTrue(has("index", "idx_assignment_content_url"));
    }

    @Test
    public void theCourseGroupAndCreatorTablesAreCreatedWithTheirKeys() throws Exception {
        runAll();
        assertTrue(has("table", "assignment_course_groups"));
        assertTrue(has("table", "assignment_creators"));
        jdbc.update("INSERT INTO assignment (id, description) VALUES (1, 'a')");
        jdbc.update("INSERT INTO person (id, uid) VALUES (1, 'p')");
        jdbc.update("INSERT INTO groups (id, name) VALUES (1, 'g')");
        jdbc.update("INSERT INTO assignment_creators VALUES (1, 1)");
        jdbc.update("INSERT INTO assignment_course_groups VALUES (1, 1)");
        Exception refused = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("INSERT INTO assignment_creators VALUES (1, 1)"));
        assertTrue(refused.getMessage().contains("constraint failed"), refused.getMessage());
    }
}
