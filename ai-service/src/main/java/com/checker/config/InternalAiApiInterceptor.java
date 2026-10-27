package com.checker.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 检查内部接口的请求头凭据，避免外部直接使用服务端配置的模型能力。 */
@Component
public class InternalAiApiInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-AI-Internal-Token";
    private final String expectedToken;

    public InternalAiApiInterceptor(@Value("${ai.internal-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    /** 未配置有效凭据时拒绝提供服务；已配置时比较请求头，不匹配则返回未认证。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (expectedToken.length() < 16) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AI_INTERNAL_TOKEN is not configured");
            return false;
        }
        String actual = request.getHeader(HEADER);
        // 使用固定时间比较方式，减少从比较耗时推测凭据内容的机会。
        boolean valid = actual != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
