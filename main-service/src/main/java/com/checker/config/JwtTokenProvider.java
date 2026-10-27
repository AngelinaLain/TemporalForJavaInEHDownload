package com.checker.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 签发和解析登录令牌，维护用户名、角色、令牌编号和密码版本等认证信息。
 */
@Component
public class JwtTokenProvider {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final SecretKey key;
    private final long expiration;

    public JwtTokenProvider(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration:86400000}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * 为管理员签发带过期时间、角色和凭据版本的登录令牌；兼容调用使用初始凭据版本。
     */
    public String generateToken(String username) {
        return generateToken(username, 0);
    }

    /**
     * 为管理员签发带过期时间、角色和凭据版本的登录令牌；兼容调用使用初始凭据版本。
     */
    public String generateToken(String username, long credentialVersion) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                // 唯一 Token ID：Redis 黑名单（注销/改密即时失效）依赖此标识
                .id(UUID.randomUUID().toString())
                // 角色声明：方法级 @PreAuthorize 权限校验依据
                .claim("roles", List.of("ADMIN"))
                .claim("credentialVersion", credentialVersion)
                .signWith(key)
                .compact();
    }

    /**
     * 从已签名的令牌中读取用户名，供请求认证使用。
     */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 读取令牌的唯一编号，供注销黑名单精确失效某个令牌。
     */
    public String getJtiFromToken(String token) {
        return parseClaims(token).getId();
    }

    /**
     * 读取令牌自然过期的时间，用于设置黑名单的保留期限。
     */
    public Date getExpirationFromToken(String token) {
        return parseClaims(token).getExpiration();
    }

    /**
     * 读取签发时的密码版本；旧令牌没有该字段时按初始版本处理，不能跨越改密继续使用。
     */
    public long getCredentialVersionFromToken(String token) {
        Object version = parseClaims(token).get("credentialVersion");
        return version == null ? 0L : version instanceof Number number ? number.longValue() : -1L;
    }

    /**
     * 读取令牌中的角色并统一补齐权限前缀，没有角色字段时返回空列表供兼容逻辑处理。
     */
    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Object roles = parseClaims(token).get("roles");
        if (roles instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role).toList();
        }
        return Collections.emptyList();
    }

    /**
     * 检查令牌签名、格式和过期时间，验证失败时返回否，避免异常直接打断请求处理。
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 使用当前签名密钥解析令牌内容，无法验证的令牌不能作为可信声明读取。
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
