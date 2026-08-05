package com.checker.controllers;

import com.checker.common.Result;
import com.checker.config.JwtTokenProvider;
import com.checker.config.SecurityProperties;
import com.checker.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    public AuthController(JwtTokenProvider jwtTokenProvider,
                          SecurityProperties securityProperties,
                          PasswordEncoder passwordEncoder,
                          LoginAttemptService loginAttemptService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.securityProperties = securityProperties;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
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
