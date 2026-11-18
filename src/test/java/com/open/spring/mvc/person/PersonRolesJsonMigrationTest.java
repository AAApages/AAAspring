package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Moving each person's roles from the person_roles join table into person.roles_json, on a real (in-memory) SQLite
 * database with the tables as Hibernate made them: quoted names, one row per person and role.
 */
public class PersonRolesJsonMigrationTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    public void database() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE \"person\" (\"id\" INTEGER PRIMARY KEY, \"uid\" TEXT)");
    }

    private void legacyTables() {
        jdbc.execute("CREATE TABLE \"person_role\" (\"id\" INTEGER PRIMARY KEY, \"name\" TEXT)");
        jdbc.execute("CREATE TABLE \"person_roles\" (\"person_id\" INTEGER, \"roles_id\" INTEGER)");
        jdbc.update("INSERT INTO \"person_role\" VALUES (1, 'ROLE_USER'), (2, 'ROLE_STUDENT'), (3, 'ROLE_TEACHER'), (4, 'ROLE_ADMIN')");
    }

    private void person(int id, String uid, int... roleIds) {
        jdbc.update("INSERT INTO \"person\" (\"id\", \"uid\") VALUES (?, ?)", id, uid);
        for (int roleId : roleIds) {
            jdbc.update("INSERT INTO \"person_roles\" VALUES (?, ?)", id, roleId);
        }
    }

    private void migrate() throws Exception {
        new PersonRolesJsonMigration(jdbc).migratePersonRolesToJson().run(null);
    }

    private String roles(String uid) {
        return jdbc.queryForObject("SELECT \"roles_json\" FROM \"person\" WHERE \"uid\" = ?", String.class, uid);
    }

    @Test
    public void everyonesRolesMoveIntoTheirJson() throws Exception {
        legacyTables();
        person(1, "student", 1, 2);
        person(2, "admin", 1, 2, 3, 4);
        person(3, "nobody");
        migrate();
        assertEquals("[\"ROLE_USER\",\"ROLE_STUDENT\"]", roles("student"));
        assertEquals("[\"ROLE_USER\",\"ROLE_STUDENT\",\"ROLE_TEACHER\",\"ROLE_ADMIN\"]", roles("admin"));
        assertEquals("[]", roles("nobody"));
    }

    @Test
    public void thePersonRolesTablesAreLeftAsTheyWere() throws Exception {
        legacyTables();
        person(1, "student", 1, 2);
        migrate();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM \"person_roles\"", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM \"person_role\"", Integer.class));
    }

    @Test
    public void runningItAgainChangesNothing() throws Exception {
        legacyTables();
        person(1, "student", 1, 2);
        migrate();
        migrate();
        assertEquals("[\"ROLE_USER\",\"ROLE_STUDENT\"]", roles("student"));
    }

    @Test
    public void rolesGivenAfterTheMoveAreNotOverwrittenByTheOldTable() throws Exception {
        legacyTables();
        person(1, "student", 1, 2);
        migrate();
        jdbc.update("UPDATE \"person\" SET \"roles_json\" = ? WHERE \"uid\" = ?", "[\"ROLE_USER\",\"ROLE_MENTOR\"]", "student");
        migrate();
        assertEquals("[\"ROLE_USER\",\"ROLE_MENTOR\"]", roles("student"));
    }

    @Test
    public void aPersonAddedAfterTheMoveWithNoRolesYetStaysEmptyAndOneWithRolesKeepsThem() throws Exception {
        legacyTables();
        migrate();
        jdbc.update("INSERT INTO \"person\" (\"id\", \"uid\", \"roles_json\") VALUES (9, 'new', '[\"ROLE_USER\"]')");
        migrate();
        assertEquals("[\"ROLE_USER\"]", roles("new"));
    }

    @Test
    public void aNewDatabaseWithNoJoinTableJustGetsEmptyArrays() throws Exception {
        jdbc.update("INSERT INTO \"person\" (\"id\", \"uid\") VALUES (1, 'first')");
        migrate();
        assertEquals("[]", roles("first"));
    }

    @Test
    public void aColumnThatIsAlreadyThereIsNotAnError() throws Exception {
        jdbc.execute("ALTER TABLE \"person\" ADD COLUMN \"roles_json\" TEXT");
        legacyTables();
        person(1, "student", 1);
        migrate();
        assertEquals("[\"ROLE_USER\"]", roles("student"));
    }

    @Test
    public void theColumnIsAddedWhenItIsMissing() throws Exception {
        legacyTables();
        person(1, "student", 2);
        assertEquals(0, jdbc.queryForList("SELECT * FROM pragma_table_info('person') WHERE name = 'roles_json'").size());
        migrate();
        assertEquals(1, jdbc.queryForList("SELECT * FROM pragma_table_info('person') WHERE name = 'roles_json'").size());
    }
}
