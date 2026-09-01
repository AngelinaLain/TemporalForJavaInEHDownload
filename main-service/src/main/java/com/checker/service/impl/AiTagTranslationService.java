package com.checker.service.impl;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 批量标签翻译服务：
 * 将多个 Tag 合并为单个 Prompt 批量请求（节约 Token、降低延迟），
 * 用 Resilience4j 断路器保护 AI 端点，熔断/失败时静默返回空映射，
 * 由调用方（本地 EH Tag 词典）继续兜底。
 */
@Slf4j
@Service
public class AiTagTranslationService {

    private static final String CB_NAME = "aiTagTranslation";

    private final RestTemplate loadBalancedRestTemplate;
    private final CircuitBreaker circuitBreaker;

    @Autowired
    public AiTagTranslationService(RestTemplate loadBalancedRestTemplate,
                                   CircuitBreakerRegistry circuitBreakerRegistry) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME);
    }

    /**
     * 批量翻译，永不抛异常；AI 不可用时返回空映射。
     *
     * @param tags 待翻译标签（本地词典未命中的部分）
     * @return 原文 → 译文 映射（可能为空）
     */
    public Map<String, String> batchTranslate(List<String> tags) {
        if (tags == null || tags.isEmpty()) return Map.of();
        try {
            Map<String, String> result = circuitBreaker.executeSupplier(() -> {
                String url = "http://eh-ai-service/api/ai/batch-translate-tags";
                Map<String, Object> req = Map.of("tags", tags);
                ResponseEntity<Map> response =
                        loadBalancedRestTemplate.postForEntity(url, req, Map.class);
                if (response.getBody() == null) return Map.<String, String>of();

                Map<String, String> normalized = new LinkedHashMap<>();
                response.getBody().forEach((k, v) -> {
                    if (k != null && v != null) normalized.put(String.valueOf(k), String.valueOf(v));
                });
                return normalized;
            });
            log.info("🌐 AI 批量翻译 {} 个标签，成功 {} 个", tags.size(), result.size());
            return result;
        } catch (Exception e) {
            log.warn("AI 批量翻译不可用（{}），使用本地词典兜底", e.getMessage());
            return Map.of();
        }
    }
}
