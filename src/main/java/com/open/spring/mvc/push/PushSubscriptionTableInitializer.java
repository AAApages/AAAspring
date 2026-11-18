package com.open.spring.mvc.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Creates push_subscription, the table behind {@link PushSubscriptionEntity}. Hibernate runs with
 * ddl-auto=none, so a new entity's table reaches a live database only through idempotent SQL
 * like this, run on every boot. The statement is unquoted so it runs on SQLite and on MySQL.
 *
 * endpoint is unique (one row per browser) and capped at {@link PushSubscriptionEntity#MAX_ENDPOINT}
 * characters: MySQL cannot index a longer utf8mb4 column, and real push service URLs are far shorter.
 */
@Component
@RequiredArgsConstructor
public class PushSubscriptionTableInitializer {

    static final String TABLE = "push_subscription";

    private static final Logger logger = LoggerFactory.getLogger(PushSubscriptionTableInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner createPushSubscriptionTable() {
        return args -> initialize();
    }

    void initialize() {
        boolean mySql = isMySql();
        String id = mySql ? "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String timestamp = mySql ? "DATETIME(6)" : "TIMESTAMP";
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + id + ", "
                    + "person_id BIGINT, "
                    + "endpoint VARCHAR(" + PushSubscriptionEntity.MAX_ENDPOINT + ") NOT NULL, "
                    + "p256dh VARCHAR(255) NOT NULL, "
                    + "auth VARCHAR(255) NOT NULL, "
                    + "user_agent VARCHAR(255), "
                    + "created_at " + timestamp + ", "
                    + "last_used_at " + timestamp + ", "
                    + "CONSTRAINT uk_push_subscription_endpoint UNIQUE (endpoint), "
                    + "CONSTRAINT fk_push_subscription_person FOREIGN KEY (person_id) "
                    + "REFERENCES person (id) ON DELETE CASCADE)");
        } catch (DataAccessException e) {
            logger.warn("Could not create the {} table: {}", TABLE, e.getMessage());
        }
    }

    private boolean isMySql() {
        try {
            String product = jdbcTemplate.execute(
                    (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            String name = product == null ? "" : product.toLowerCase();
            return name.contains("mysql") || name.contains("mariadb");
        } catch (DataAccessException e) {
            return false;
        }
    }
}
