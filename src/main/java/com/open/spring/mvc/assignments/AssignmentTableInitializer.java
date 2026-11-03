package com.open.spring.mvc.assignments;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Adds the closure and team_teach columns to assignment for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class AssignmentTableInitializer {

    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public ApplicationRunner migrateAssignmentClosure() {
        return args -> initialize();
    }

    void initialize() {
        TableSchema schema = TableSchema.of(jdbcTemplate);
        schema.addColumns("assignment", "closed_at " + schema.timestamp(), "closed_by_id BIGINT",
                "team_teach BOOLEAN");
    }
}
