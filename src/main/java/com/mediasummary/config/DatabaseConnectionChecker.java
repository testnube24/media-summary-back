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

    @Bean
    public ApplicationRunner validateDatabaseConnection() {
        return args -> {
            try (var conn = jdbcTemplate.getDataSource().getConnection()) {
                Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

                if (result != null && result == 1) {
                    log.info("Database connected successfully.");
                } else {
                    log.warn("Database connection validation returned unexpected result: {}", result);
                }

            } catch (Exception e) {
                log.error("Database connection failed: {}", e.getMessage());
                throw e;
            }
        };
    }
}
