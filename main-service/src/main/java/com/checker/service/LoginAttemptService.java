package com.checker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 限制在单个应用程序实例中重复失败的登录。
 * 分布式部署应该用一个共享的redis支持的限制器来取代它。
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

    /** 同时检查账号和来源地址的失败次数，任一达到阈值就暂时限制尝试。 */
    public boolean isBlocked(String username, String clientAddress) {
        return blocked.getIfPresent(usernameKey(username)) != null
                || blocked.getIfPresent(addressKey(clientAddress)) != null;
    }

    /** 分别增加账号与地址的失败计数，让换账号或换来源的尝试也受限制。 */
    public void recordFailure(String username, String clientAddress) {
        recordFailure(usernameKey(username));
        recordFailure(addressKey(clientAddress));
    }

    /** 登录成功后清除当前账号和地址的失败计数。 */
    public void reset(String username, String clientAddress) {
        failures.invalidate(usernameKey(username));
        failures.invalidate(addressKey(clientAddress));
    }

    /** 分别增加账号与地址的失败计数，让换账号或换来源的尝试也受限制。 */
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
