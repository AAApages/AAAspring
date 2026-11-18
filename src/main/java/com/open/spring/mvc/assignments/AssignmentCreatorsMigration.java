package com.open.spring.mvc.assignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Creates the assignment-to-creator ("lesson owner") join table for databases managed with
 * ddl-auto=none. Without this, AssignmentCreatorSyncService.applyCreators() fails with
 * "no such table: assignment_creators" the moment the Pages sync bot tries to set an
 * assignment's owners - the request 500s, the per-file sync safety net
 * (create_assignments_from_frontmatter.py) logs and swallows it as a warning rather than
 * failing the run, and the creators list silently never gets written. A teacher who set
 * assignment_creator_uids in frontmatter would see the sync "succeed" overall while the
 * student(s) they named never actually show up as owners anywhere.
 *
 * Mirrors AssignmentCourseGroupsMigration exactly (same sibling join-table problem, same
 * fix) - assignment_person (assignedGraders) and assignment_course_groups already exist on
 * every environment that predates ddl-auto=none or already had this migration; this table
 * was the one left out.
 */
@Component
@RequiredArgsConstructor
public class AssignmentCreatorsMigration {

    private static final Logger logger = LoggerFactory.getLogger(AssignmentCreatorsMigration.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateAssignmentCreators() {
        return args -> {
            try {
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS assignment_creators ("
                        + "assignment_id bigint NOT NULL, "
                        + "person_id bigint NOT NULL, "
                        + "PRIMARY KEY (assignment_id, person_id), "
                        + "FOREIGN KEY (assignment_id) REFERENCES assignment (id), "
                        + "FOREIGN KEY (person_id) REFERENCES person (id))");
            } catch (DataAccessException e) {
                logger.warn("Could not create assignment creators table: {}", e.getMessage());
            }
        };
    }
}
