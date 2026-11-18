package com.open.spring.mvc.person;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Moves each person's roles from the person_roles join table into one JSON array in person.roles_json.
 *
 * Hibernate runs with ddl-auto=none, so the new column and its data reach a live database only through hand-written
 * idempotent SQL like this. The statements are unquoted, as in TableSchema, so they run on SQLite and on MySQL: MySQL
 * reads a double-quoted name as a string unless ANSI_QUOTES is on, and an error here is swallowed, which would leave
 * the column missing. It runs first on every boot and is a no-op once
 * applied: a person whose roles_json is already filled in is never touched, and a database that never had the join
 * table (a new one) just gets empty arrays.
 *
 * The person_roles and person_role tables are left as they are. person_role stays as the catalogue of role names the
 * admin page lists; person_roles is no longer read or written, so it keeps the roles as they were at the move and is
 * what an older build would still read.
 */
@Component
@RequiredArgsConstructor
public class PersonRolesJsonMigration {

    private static final Logger logger = LoggerFactory.getLogger(PersonRolesJsonMigration.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner migratePersonRolesToJson() {
        return args -> {
            addRolesJsonColumn();
            fillFromJoinTable();
            jdbcTemplate.update("UPDATE person SET roles_json = '[]' WHERE roles_json IS NULL");
        };
    }

    private void addRolesJsonColumn() {
        try {
            jdbcTemplate.execute("ALTER TABLE person ADD COLUMN roles_json TEXT");
            logger.info("Added 'roles_json' column to 'person' table");
        } catch (DataAccessException ignored) {
            // Column already exists on every boot after the first.
        }
    }

    private void fillFromJoinTable() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList("SELECT pr.person_id AS person_id, r.name AS name "
                    + "FROM person_roles pr JOIN person_role r ON r.id = pr.roles_id ORDER BY pr.person_id, r.id");
        } catch (DataAccessException noJoinTable) {
            return;  // a new database: nothing to move
        }
        Map<Object, List<String>> byPerson = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byPerson.computeIfAbsent(row.get("person_id"), id -> new ArrayList<>()).add(String.valueOf(row.get("name")));
        }
        int moved = 0;
        for (Map.Entry<Object, List<String>> entry : byPerson.entrySet()) {
            try {
                moved += jdbcTemplate.update("UPDATE person SET roles_json = ? WHERE id = ? AND roles_json IS NULL",
                        MAPPER.writeValueAsString(entry.getValue()), entry.getKey());
            } catch (Exception e) {
                throw new IllegalStateException("Could not move the roles of person " + entry.getKey() + " to JSON", e);
            }
        }
        if (moved > 0) {
            logger.info("Moved the roles of {} people into person.roles_json", moved);
        }
    }
}
