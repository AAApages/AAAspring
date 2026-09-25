package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Creates the assignment-to-course join table for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class AssignmentCourseGroupsMigration {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentCourseGroupsMigration.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateAssignmentCourseGroups() {
        return args -> {
            try {
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS \"assignment_course_groups\" ("
                        + "\"assignment_id\" bigint NOT NULL, "
                        + "\"group_id\" bigint NOT NULL, "
                        + "PRIMARY KEY (\"assignment_id\", \"group_id\"), "
                        + "FOREIGN KEY (\"assignment_id\") REFERENCES \"assignment\" (\"id\"), "
                        + "FOREIGN KEY (\"group_id\") REFERENCES \"groups\" (\"id\"))");
            } catch (DataAccessException e) {
                logger.warn("Could not create assignment course-group table: {}", e.getMessage());
            }
        };
    }
}
