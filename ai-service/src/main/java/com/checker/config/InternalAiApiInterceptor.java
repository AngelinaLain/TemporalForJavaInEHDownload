package com.checker.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalAiApiInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-AI-Internal-Token";
    private final String expectedToken;

    public InternalAiApiInterceptor(@Value("${ai.internal-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (expectedToken.length() < 16) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AI_INTERNAL_TOKEN is not configured");
            return false;
        }
        String actual = request.getHeader(HEADER);
        boolean valid = actual != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
