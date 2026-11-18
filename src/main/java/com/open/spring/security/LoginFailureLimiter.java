package com.open.spring.security;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * Caps wrong passwords per account per IP. RateLimitFilter caps /authenticate per IP only
 * (300 a minute, so a class behind one school IP can log in at once), which still lets one
 * machine try hundreds of passwords a minute on a single account.
 *
 * The key is uid plus IP, so someone guessing from another machine cannot lock the real
 * student out. A successful login clears the count.
 */
@Component
public class LoginFailureLimiter {

    // Bounds memory when uids that do not exist are tried; clearing only forgets old failures.
    private static final int MAX_TRACKED = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Map<String, Bucket> failures = new ConcurrentHashMap<>();

    public LoginFailureLimiter(
            @Value("${security.rate-limit.login-failures-per-account:10}") int maxFailures,
            @Value("${security.rate-limit.login-failure-window-minutes:15}") int windowMinutes) {
        this.maxFailures = maxFailures;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    /** True when this uid has used up its wrong passwords from this IP; skip the password check. */
    public boolean isBlocked(String uid, String ip) {
        Bucket bucket = failures.get(key(uid, ip));
        return bucket != null && bucket.getAvailableTokens() <= 0;
    }

    public void recordFailure(String uid, String ip) {
        if (failures.size() >= MAX_TRACKED) {
            failures.clear();
        }
        failures.computeIfAbsent(key(uid, ip), k -> newBucket()).tryConsume(1);
    }

    public void recordSuccess(String uid, String ip) {
        failures.remove(key(uid, ip));
    }

    public long windowMinutes() {
        return window.toMinutes();
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(maxFailures).refillGreedy(maxFailures, window).build())
                .build();
    }

    private static String key(String uid, String ip) {
        return uid.toLowerCase(Locale.ROOT) + "|" + ip;
    }
}
