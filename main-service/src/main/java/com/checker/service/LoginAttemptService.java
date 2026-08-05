package com.checker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Limits repeated failed logins within a single application instance.
 * A distributed deployment should replace this with a shared Redis-backed limiter.
 */
@Component
public class LoginAttemptService {
    private static final int MAX_FAILURES = 5;
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(15);

    private final Cache<String, Integer> failures = Caffeine.newBuilder()
            .expireAfterWrite(BLOCK_DURATION)
            .maximumSize(10_000)
            .build();
    private final Cache<String, Boolean> blocked = Caffeine.newBuilder()
            .expireAfterWrite(BLOCK_DURATION)
            .maximumSize(10_000)
            .build();

    public boolean isBlocked(String username, String clientAddress) {
        return blocked.getIfPresent(usernameKey(username)) != null
                || blocked.getIfPresent(addressKey(clientAddress)) != null;
    }

    public void recordFailure(String username, String clientAddress) {
        recordFailure(usernameKey(username));
        recordFailure(addressKey(clientAddress));
    }

    public void reset(String username, String clientAddress) {
        failures.invalidate(usernameKey(username));
        failures.invalidate(addressKey(clientAddress));
    }

    private void recordFailure(String key) {
        int count = failures.asMap().merge(key, 1, Integer::sum);
        if (count >= MAX_FAILURES) {
            blocked.put(key, Boolean.TRUE);
            failures.invalidate(key);
        }
    }

    private String usernameKey(String username) {
        return "username:" + username;
    }

    private String addressKey(String clientAddress) {
        return "address:" + clientAddress;
    }
}
