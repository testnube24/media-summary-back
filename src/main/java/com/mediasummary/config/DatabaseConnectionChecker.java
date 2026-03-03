package com.mediasummary.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConnectionChecker {

    private final JdbcTemplate jdbcTemplate;
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionChecker.class);

    public DatabaseConnectionChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs at application startup and validates DB connectivity by running a lightweight query.
     * Logs success or failure. To fail the application startup on DB errors, rethrow the exception.
     */
    @Bean
    public ApplicationRunner validateDatabaseConnection() {
        return args -> {
            try {
                Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                if (result != null && result == 1) {
                    log.info("Database connection validated at startup (SELECT 1 returned 1).");
                } else {
                    log.warn("Database validation returned unexpected result: {}", result);
                }
            } catch (Exception e) {
                log.error("Failed to connect to the database on startup:", e);
                // If you want the app to fail fast when the DB is unreachable, uncomment the next line:
                // throw e;
            }
        };
    }
}
