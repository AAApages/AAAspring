package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The same migration on a real MySQL, where a double-quoted name is a string unless ANSI_QUOTES is on and a failed
 * statement is swallowed. Off unless ROLES_TEST_MYSQL_URL is set, and it DROPS the person, person_role and
 * person_roles tables of that database, so point it at an empty throwaway one:
 *
 *   docker run -d --name rolesjson-mysql -e MYSQL_ROOT_PASSWORD=rolestest -e MYSQL_DATABASE=rolestest -p 127.0.0.1:3399:3306 mysql:8.0
 *   ROLES_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3399/rolestest' ROLES_TEST_MYSQL_USER=root ROLES_TEST_MYSQL_PASSWORD=rolestest \
 *       ./mvnw -Dtest=PersonRolesJsonMigrationMySqlTest test
 */
@EnabledIfEnvironmentVariable(named = "ROLES_TEST_MYSQL_URL", matches = ".+")
public class PersonRolesJsonMigrationMySqlTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    public void database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(System.getenv("ROLES_TEST_MYSQL_URL"),
                System.getenv().getOrDefault("ROLES_TEST_MYSQL_USER", "root"), System.getenv().getOrDefault("ROLES_TEST_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        drop();
        // as Hibernate makes them (schema_full.txt)
        jdbc.execute("CREATE TABLE person (id BIGINT NOT NULL, uid VARCHAR(255) NOT NULL UNIQUE, PRIMARY KEY (id))");
        jdbc.execute("CREATE TABLE person_role (id BIGINT NOT NULL, name VARCHAR(255) UNIQUE, PRIMARY KEY (id))");
        jdbc.execute("CREATE TABLE person_roles (person_id BIGINT NOT NULL, roles_id BIGINT NOT NULL)");
        jdbc.update("INSERT INTO person_role VALUES (1, 'ROLE_USER'), (2, 'ROLE_STUDENT'), (3, 'ROLE_TEACHER')");
        jdbc.update("INSERT INTO person VALUES (1, 'student'), (2, 'teacher'), (3, 'nobody')");
        jdbc.update("INSERT INTO person_roles VALUES (1, 1), (1, 2), (2, 1), (2, 3)");
    }

    @AfterEach
    public void drop() {
        jdbc.execute("DROP TABLE IF EXISTS person_roles");
        jdbc.execute("DROP TABLE IF EXISTS person_role");
        jdbc.execute("DROP TABLE IF EXISTS person");
    }

    private void migrate() throws Exception {
        new PersonRolesJsonMigration(jdbc).migratePersonRolesToJson().run(null);
    }

    private String roles(String uid) {
        return jdbc.queryForObject("SELECT roles_json FROM person WHERE uid = ?", String.class, uid);
    }

    @Test
    public void theColumnIsAddedAndEveryonesRolesMove() throws Exception {
        migrate();
        assertEquals("[\"ROLE_USER\",\"ROLE_STUDENT\"]", roles("student"));
        assertEquals("[\"ROLE_USER\",\"ROLE_TEACHER\"]", roles("teacher"));
        assertEquals("[]", roles("nobody"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM person_roles", Integer.class));
    }

    @Test
    public void runningItAgainChangesNothingAndDoesNotOverwriteLaterRoles() throws Exception {
        migrate();
        jdbc.update("UPDATE person SET roles_json = ? WHERE uid = 'student'", "[\"ROLE_USER\",\"ROLE_MENTOR\"]");
        migrate();
        assertEquals("[\"ROLE_USER\",\"ROLE_MENTOR\"]", roles("student"));
        assertEquals("[\"ROLE_USER\",\"ROLE_TEACHER\"]", roles("teacher"));
    }
}
