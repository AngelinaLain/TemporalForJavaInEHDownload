package com.checker.controllers;

import com.checker.common.Result;
import com.checker.config.JwtTokenProvider;
import com.checker.config.SecurityProperties;
import lombok.Data;
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

    public AuthController(JwtTokenProvider jwtTokenProvider, SecurityProperties securityProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.securityProperties = securityProperties;
    }

    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody LoginRequest loginRequest) {
        if (securityProperties.getAdmin().getUsername().equals(loginRequest.getUsername())
                && securityProperties.getAdmin().getPassword().equals(loginRequest.getPassword())) {
            String token = jwtTokenProvider.generateToken(loginRequest.getUsername());
            return Result.success(Map.of(
                    "token", token,
                    "username", loginRequest.getUsername()
            ));
        }
        return Result.error(401, "用户名或密码错误");
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
