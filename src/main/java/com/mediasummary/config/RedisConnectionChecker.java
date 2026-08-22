package com.mediasummary.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConnectionChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionChecker.class);

    private final StringRedisTemplate redisTemplate;

    public RedisConnectionChecker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Bean
    public ApplicationRunner validateRedisConnection() {
        return args -> {
            try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
                connection.ping();
                log.info("Redis connected successfully.");

            } catch (Exception e) {
                // Do not abort startup: the API can still serve status/health from Postgres
                // while the queue is unreachable. Uploads fail per-request until Redis is back.
                log.error("Redis connection failed, queue is unavailable: {}", e.getMessage());
            }
        };
    }
}
