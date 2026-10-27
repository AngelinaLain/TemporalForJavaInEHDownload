package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.checker.common.Result;
import com.checker.config.JwtTokenProvider;
import com.checker.service.AdminCredentialService;
import com.checker.service.LoginAttemptService;
import com.checker.service.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.OptionalLong;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "登录、登出与访问令牌管理")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final AdminCredentialService credentials;
    private final LoginAttemptService loginAttemptService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(JwtTokenProvider jwtTokenProvider,
                          AdminCredentialService credentials,
                          LoginAttemptService loginAttemptService,
                          TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.credentials = credentials;
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

        OptionalLong version;
        try {
            version = credentials.authenticate(username, loginRequest.getPassword());
        } catch (DataAccessException unavailable) {
            return Result.error(503, "账号服务暂不可用，请稍后重试");
        }
        if (version.isPresent()) {
            loginAttemptService.reset(username, clientAddress);
            String token = jwtTokenProvider.generateToken(username, version.getAsLong());
            return Result.success(Map.of(
                    "token", token,
                    "username", username
            ));
        }

        loginAttemptService.recordFailure(username, clientAddress);
        return Result.error(401, "用户名或密码错误");
    }

    @PostMapping("/change-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest body,
                                                       HttpServletRequest request,
                                                       Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return Result.error(401, "请先登录");
        String username = authentication.getName();
        String attemptKey = "password-change:" + username;
        String clientKey = "password-change:" + request.getRemoteAddr();
        if (loginAttemptService.isBlocked(attemptKey, clientKey))
            return Result.error(429, "当前密码验证失败次数过多，请 15 分钟后重试");
        try {
            credentials.changePassword(username, body.getCurrentPassword(), body.getNewPassword(), body.getConfirmPassword());
            loginAttemptService.reset(attemptKey, clientKey);
            return Result.success(Map.of("message", "密码已修改，请重新登录", "relogin", true));
        } catch (AdminCredentialService.InvalidCurrentPasswordException incorrect) {
            loginAttemptService.recordFailure(attemptKey, clientKey);
            return Result.error(400, incorrect.getMessage());
        } catch (IllegalArgumentException invalid) {
            return Result.error(400, invalid.getMessage());
        } catch (IllegalStateException conflict) {
            return Result.error(409, conflict.getMessage());
        } catch (DataAccessException unavailable) {
            return Result.error(503, "账号服务暂不可用，请稍后重试");
        }
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
        @ToString.Exclude
        private String password;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "请输入当前密码")
        @Size(max = 256, message = "当前密码过长")
        @ToString.Exclude
        private String currentPassword;
        @NotBlank(message = "请输入新密码")
        @Size(max = 72, message = "新密码过长")
        @ToString.Exclude
        private String newPassword;
        @NotBlank(message = "请再次输入新密码")
        @Size(max = 72, message = "确认密码过长")
        @ToString.Exclude
        private String confirmPassword;
    }
}
