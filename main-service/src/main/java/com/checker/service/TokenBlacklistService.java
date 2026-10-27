package com.checker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单服务：
 * <ul>
 *   <li>Redis 可用时：黑名单存储于 Redis（key = jti，TTL = Token 剩余有效期），
 *       让注销操作在多个实例间立即生效；改密后的旧会话另由凭据版本校验拒绝；</li>
 *   <li>Redis 不可用时：自动降级为本地 Caffeine 缓存（单实例内生效）。</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String REDIS_KEY_PREFIX = "eh:jwt:blacklist:";

    /** 共享存储不可用时的本地黑名单；缓存按容量淘汰，令牌自身仍会校验有效期。 */
    private final Cache<String, Boolean> fallbackCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .build();

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    /**
     * 将 Token 拉入黑名单，直到其自然过期。
     */
    public void blacklist(String jti, Date tokenExpiration) {
        if (jti == null || jti.isBlank()) return;
        long ttlSeconds = Math.max(1,
                (tokenExpiration.getTime() - System.currentTimeMillis()) / 1000);

        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + jti, "1",
                        Duration.ofSeconds(ttlSeconds));
                log.info("✅ Token 已加入 Redis 黑名单，剩余有效期 {} 秒", ttlSeconds);
                return;
            } catch (Exception e) {
                log.warn("Redis 黑名单写入失败，降级本地缓存: {}", e.getMessage());
            }
        }
        fallbackCache.put(jti, Boolean.TRUE);
        log.info("✅ Token 已加入本地黑名单（Redis 不可用），剩余有效期 {} 秒", ttlSeconds);
    }

    /**
     * 判断 Token 是否已被注销（黑名单命中）。
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) return false;
        if (fallbackCache.getIfPresent(jti) != null) return true;
        if (redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_KEY_PREFIX + jti));
            } catch (Exception e) {
                log.debug("Redis 黑名单查询失败: {}", e.getMessage());
            }
        }
        return false;
    }
}
