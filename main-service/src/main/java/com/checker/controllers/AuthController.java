package com.checker.controllers;

import com.checker.common.Result;
import com.checker.config.JwtTokenProvider;
import com.checker.config.SecurityProperties;
import com.checker.service.LoginAttemptService;
import com.checker.service.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final SecurityProperties securityProperties;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(JwtTokenProvider jwtTokenProvider,
                          SecurityProperties securityProperties,
                          PasswordEncoder passwordEncoder,
                          LoginAttemptService loginAttemptService,
                          TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.securityProperties = securityProperties;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @PostMapping("/login")
    public Result<Map<String, String>> login(@Valid @RequestBody LoginRequest loginRequest,
                                              HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String clientAddress = request.getRemoteAddr();
        if (loginAttemptService.isBlocked(username, clientAddress)) {
            return Result.error(429, "登录尝试过多，请 15 分钟后重试");
        }

        boolean authenticated = securityProperties.getAdmin().getUsername().equals(username)
                && passwordEncoder.matches(loginRequest.getPassword(), securityProperties.getAdmin().getPasswordHash());
        if (authenticated) {
            loginAttemptService.reset(username, clientAddress);
            String token = jwtTokenProvider.generateToken(username);
            return Result.success(Map.of(
                    "token", token,
                    "username", username
            ));
        }

        loginAttemptService.recordFailure(username, clientAddress);
        return Result.error(401, "用户名或密码错误");
    }

    /**
     * 注销：将当前 Token 加入黑名单（Redis，降级为本地缓存），使其立即失效，
     * 直到 Token 自然过期后黑名单条目自动清理。
     */
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            tokenBlacklistService.blacklist(
                    jwtTokenProvider.getJtiFromToken(token),
                    jwtTokenProvider.getExpirationFromToken(token));
            return Result.success("注销成功，Token 已失效");
        }
        return Result.error(400, "未携带有效 Token");
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64 个字符")
        private String username;
        @NotBlank(message = "密码不能为空")
        @Size(max = 256, message = "密码长度不能超过 256 个字符")
        private String password;
    }
}
