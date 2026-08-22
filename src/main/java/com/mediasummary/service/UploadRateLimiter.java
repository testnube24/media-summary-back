package com.mediasummary.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Fixed-window rate limiter for uploads, keyed by client IP.
 *
 * State is per-instance and in memory on purpose: it must keep working when Redis
 * is unreachable, which is exactly when the queue is already degraded. With more
 * than one API instance the effective limit is the configured value times the
 * number of instances.
 */
@Service
@Slf4j
public class UploadRateLimiter {

    @Value("${app.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${app.ratelimit.max-uploads:10}")
    private int maxUploads;

    @Value("${app.ratelimit.window-minutes:60}")
    private long windowMinutes;

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public Decision check(String clientKey) {
        if (!enabled) {
            return Decision.allowed();
        }

        long now = System.currentTimeMillis();
        long windowMs = TimeUnit.MINUTES.toMillis(windowMinutes);

        Window window = windows.compute(clientKey, (key, current) -> {
            if (current == null || now - current.startedAt >= windowMs) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.attempts + 1);
        });

        if (window.attempts <= maxUploads) {
            return Decision.allowed();
        }

        long elapsed = now - window.startedAt;
        long retryAfterSeconds = Math.max(1, (windowMs - elapsed) / 1000);
        return Decision.denied(retryAfterSeconds);
    }

    /** Drops windows that already expired so the map does not grow with every new IP. */
    @Scheduled(fixedDelayString = "${app.ratelimit.cleanup-ms:600000}")
    public void purgeExpiredWindows() {
        long windowMs = TimeUnit.MINUTES.toMillis(windowMinutes);
        long now = System.currentTimeMillis();
        int before = windows.size();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= windowMs);
        int removed = before - windows.size();
        if (removed > 0) {
            log.debug("Purged {} expired rate limit windows, {} still tracked", removed, windows.size());
        }
    }

    private static final class Window {
        private final long startedAt;
        private final int attempts;

        private Window(long startedAt, int attempts) {
            this.startedAt = startedAt;
            this.attempts = attempts;
        }
    }

    @lombok.Value
    public static class Decision {
        boolean allowed;
        long retryAfterSeconds;

        static Decision allowed() {
            return new Decision(true, 0);
        }

        static Decision denied(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
