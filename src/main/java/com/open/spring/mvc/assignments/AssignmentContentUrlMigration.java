package com.open.spring.mvc.assignments;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Gives the assignment table a real, indexed content_url column and fills it in from the
 * legacy description marker.
 *
 * Auto-created assignments used to be identified by writing "[CONTENT_URL: &lt;url&gt;]" into the
 * description and dedup-matching on that prefix, which meant every auto-create request read
 * the whole assignment table. The column replaces that with a single indexed lookup.
 *
 * Hibernate runs with ddl-auto=none, so entity annotations never reach a live database.
 * The established pattern for schema changes here is hand-written idempotent SQL with
 * swallowed exceptions (see ModelInit.run() and
 * AssignmentsApiController.normalizeAssignmentSequenceForSqlite()), which is what this does.
 * Identifiers are quoted to match hibernate.globally_quoted_identifiers=true.
 *
 * Every step is safe to repeat: it runs on each boot and is a no-op once applied.
 */
@Component
@RequiredArgsConstructor
public class AssignmentContentUrlMigration {

    /** Prefix the old dedup scheme wrote into description. Read here, never written again. */
    static final String LEGACY_MARKER_PREFIX = "[CONTENT_URL: ";

    private static final Logger logger = LoggerFactory.getLogger(AssignmentContentUrlMigration.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateAssignmentContentUrls() {
        return args -> {
            addContentUrlColumn();
            backfillContentUrlsFromDescription();
            createContentUrlUniqueIndex();
        };
    }

    private void addContentUrlColumn() {
        try {
            jdbcTemplate.execute("ALTER TABLE \"assignment\" ADD COLUMN \"content_url\" varchar(255)");
            logger.info("Added 'content_url' column to 'assignment' table");
        } catch (DataAccessException ignored) {
            // Column already exists on every boot after the first.
        }
    }

    /**
     * Makes content_url uniqueness a real database constraint instead of only the
     * application-level check-then-insert in AssignmentsApiController.autoCreateAssignment().
     * That check queries for an existing row, and only inserts a new one if none is found -
     * two concurrent auto-create requests for the same page (e.g. a class opening the same
     * lesson at once, each firing the request on page load) can both pass that check before
     * either commits, producing two real duplicate rows. A unique index turns the loser's
     * insert into a constraint violation instead, which autoCreateAssignment() catches and
     * turns into the same "assignment already exists" response the winner gets.
     */
    private void createContentUrlUniqueIndex() {
        if (duplicateContentUrlsExist()) {
            // Refuse to leave a half-applied unique index: SQLite and MySQL both reject
            // CREATE UNIQUE INDEX outright when existing rows violate it, so this would
            // otherwise fail silently (same swallowed-exception path as every other
            // statement here) and never actually protect anything. Surfaced loudly instead
            // so duplicates get cleaned up manually - deleting assignment rows here would
            // orphan their submissions/grades, which this migration must not do on its own.
            logger.warn(
                "Duplicate content_url values exist in 'assignment' - skipping the unique "
                    + "index. Auto-create duplication is NOT fully prevented until these are "
                    + "resolved manually. Query: SELECT content_url, COUNT(*) FROM assignment "
                    + "WHERE content_url IS NOT NULL GROUP BY content_url HAVING COUNT(*) > 1");
            return;
        }

        // A database that already ran the old migration has a plain (non-unique) index
        // under this exact name - "IF NOT EXISTS" would see the name taken and skip
        // creation, silently leaving it non-unique forever. Drop it first so the name is
        // free; harmless no-op on a fresh database that never had it. Two dialect-specific
        // forms because SQLite and MySQL spell "drop index" differently; each is tried
        // independently so one dialect rejecting its form doesn't block the other's.
        try {
            jdbcTemplate.execute("DROP INDEX IF EXISTS \"idx_assignment_content_url\"");
        } catch (DataAccessException ignored) {
            // Not SQLite, or the index never existed under this form.
        }
        try {
            jdbcTemplate.execute("DROP INDEX \"idx_assignment_content_url\" ON \"assignment\"");
        } catch (DataAccessException ignored) {
            // Not MySQL, or the index never existed under this form.
        }

        // Kept separate from the ALTER so that a database rejecting one statement still
        // gets the other. MySQL has no IF NOT EXISTS for CREATE INDEX, so re-running this
        // on an already-migrated database lands here as a swallowed duplicate-key error
        // rather than a startup failure.
        try {
            jdbcTemplate.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS \"idx_assignment_content_url\" "
                    + "ON \"assignment\" (\"content_url\")");
        } catch (DataAccessException ignored) {
            // Index already exists, or the dialect refuses IF NOT EXISTS.
        }
    }

    private boolean duplicateContentUrlsExist() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT \"content_url\" FROM \"assignment\" "
                    + "WHERE \"content_url\" IS NOT NULL GROUP BY \"content_url\" "
                    + "HAVING COUNT(*) > 1) \"dup\"",
                Integer.class);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            // If we can't even check, don't risk creating a unique index blind.
            logger.warn("Could not check for duplicate content_url values: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Copies the URL out of the legacy description marker into the new column.
     *
     * Descriptions are intentionally left untouched: the marker is redundant once the
     * column is populated, but rewriting live rows buys nothing and cannot be undone.
     */
    private void backfillContentUrlsFromDescription() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                "SELECT \"id\", \"description\" FROM \"assignment\" "
                    + "WHERE \"content_url\" IS NULL AND \"description\" LIKE '[CONTENT_URL: %'");
        } catch (DataAccessException e) {
            logger.warn("Skipped assignment content_url backfill: {}", e.getMessage());
            return;
        }

        int updated = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            Object description = row.get("description");
            if (id == null || description == null) {
                continue;
            }

            String contentUrl = AssignmentContentUrls.canonicalize(
                extractMarkedContentUrl(description.toString()));
            if (contentUrl == null) {
                continue;
            }

            try {
                updated += jdbcTemplate.update(
                    "UPDATE \"assignment\" SET \"content_url\" = ? WHERE \"id\" = ?",
                    contentUrl, id);
            } catch (DataAccessException e) {
                logger.warn("Could not backfill content_url for assignment {}: {}", id, e.getMessage());
            }
        }

        if (updated > 0) {
            logger.info("Backfilled content_url for {} assignment(s) from the legacy description marker", updated);
        }
    }

    /**
     * @return the URL inside "[CONTENT_URL: &lt;url&gt;]", or null when the description is not
     *         actually marked or the marker was truncated.
     */
    static String extractMarkedContentUrl(String description) {
        if (description == null || !description.startsWith(LEGACY_MARKER_PREFIX)) {
            return null;
        }
        int close = description.indexOf(']', LEGACY_MARKER_PREFIX.length());
        if (close < 0) {
            return null;
        }
        return description.substring(LEGACY_MARKER_PREFIX.length(), close);
    }
}
