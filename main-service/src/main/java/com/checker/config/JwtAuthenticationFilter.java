package com.checker.config;

import com.checker.service.TokenBlacklistService;
import com.checker.service.AdminCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 检查每个请求携带的登录令牌，并把有效的管理员身份交给后续权限校验。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final AdminCredentialService credentials;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   TokenBlacklistService tokenBlacklistService,
                                   AdminCredentialService credentials) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.credentials = credentials;
    }

    /**
     * 依次检查令牌签名和过期时间、注销黑名单，以及账号当前的密码版本。
     * 修改密码后旧版本令牌不能继续认证；账号存储不可用时返回服务暂不可用，不绕过验证。
     * 验证通过后才把用户名和角色放入当前请求的安全上下文，无效令牌由后续权限规则处理。
     */
    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)
                && !tokenBlacklistService.isBlacklisted(jwtTokenProvider.getJtiFromToken(token))) {
            String username = jwtTokenProvider.getUsernameFromToken(token);
            try {
                if (!credentials.isTokenCurrent(username, jwtTokenProvider.getCredentialVersionFromToken(token))) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }
            } catch (DataAccessException unavailable) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":503,\"msg\":\"账号服务暂不可用，请稍后重试\"}");
                return;
            }
            // 角色来自 Token 声明；旧版 Token 无声明时回退为 ROLE_ADMIN
            List<String> roles = jwtTokenProvider.getRolesFromToken(token);
            if (roles.isEmpty()) {
                roles = List.of(JwtTokenProvider.ROLE_ADMIN);
            }
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求的认证头中提取 Bearer 令牌，没有符合格式的请求头时返回空值。
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
