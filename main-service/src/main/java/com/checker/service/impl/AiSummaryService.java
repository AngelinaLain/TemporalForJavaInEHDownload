package com.checker.service.impl;

import com.checker.service.EhTagTranslationService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 摘要服务：
 * <ul>
 *   <li>Resilience4j 断路器保护：AI 端点连续超时/报错触发熔断；</li>
 *   <li>熔断打开时自动降级为本地词典兜底（EH Tag 翻译库 + 英文 Tag 透传）；</li>
 *   <li>瞬时 503 仍上抛，交由 Temporal 重试（GPU 节点短暂离线场景）。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiSummaryService {

    private static final String CB_NAME = "aiSummary";

    private final RestTemplate loadBalancedRestTemplate;
    private final CircuitBreaker circuitBreaker;
    private final EhTagTranslationService tagTranslationService;

    @Autowired
    public AiSummaryService(RestTemplate loadBalancedRestTemplate,
                            CircuitBreakerRegistry circuitBreakerRegistry,
                            EhTagTranslationService tagTranslationService) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME);
        this.tagTranslationService = tagTranslationService;
    }

    /**
     * 生成摘要，永不抛异常：
     * 熔断打开或最终失败时返回本地词典兜底文本；瞬时 503 重新上抛交 Temporal 重试。
     */
    public String generateSummaryWithFallback(String title, List<String> tags) {
        try {
            return circuitBreaker.executeSupplier(() -> {
                String url = "http://eh-ai-service/api/ai/generate-summary";
                Map<String, Object> req = new HashMap<>();
                req.put("title", title);
                req.put("tags", tags);
                String summary = loadBalancedRestTemplate.postForObject(url, req, String.class);
                if (summary == null || summary.isBlank()) {
                    throw new IllegalStateException("AI 返回空摘要");
                }
                return summary;
            });
        } catch (CallNotPermittedException e) {
            log.warn("🧯 AI 断路器已打开，降级为本地词典摘要: {}", title);
            return buildFallbackSummary(title, tags);
        } catch (HttpServerErrorException.ServiceUnavailable e) {
            log.warn("💤 GPU节点离线，触发挂起重试");
            throw e;
        } catch (Exception e) {
            log.warn("🤖 AI 摘要生成失败（{}），降级为本地词典摘要", e.getMessage());
            return buildFallbackSummary(title, tags);
        }
    }

    /**
     * 本地离线词典兜底：标题直出 + 标签经 EH Tag 翻译库匹配（未命中则透传英文 Tag）。
     */
    private String buildFallbackSummary(String title, List<String> tags) {
        String tagText = tags == null || tags.isEmpty()
                ? "无标签"
                : tags.stream()
                        .limit(20)
                        .map(tagTranslationService::translate)
                        .collect(Collectors.joining("、"));
        return String.format("《%s》。标签：%s。", title, tagText);
    }
}
