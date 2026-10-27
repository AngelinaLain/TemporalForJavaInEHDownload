package com.checker.service;

import com.checker.dto.VisualEmbedding;
import com.checker.entity.AiProviderConfigEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 统一调用内部 AI 服务，按供应商限制并发，并把模型配置和图片整理成约定的请求格式。 */
@Service
public class AiInternalClient {
    private final RestTemplate restTemplate;
    private final AiProviderSecretCipher cipher;
    private final Map<String, Semaphore> providerLimits = new ConcurrentHashMap<>();

    public AiInternalClient(RestTemplate restTemplate, AiProviderSecretCipher cipher) {
        this.restTemplate = restTemplate;
        this.cipher = cipher;
    }

    /** 查询指定供应商可用模型，并受该供应商的并发限额约束。 */
    public List<String> models(AiProviderConfigEntity provider) {
        return withPermit(provider, () -> {
            Map<String, Object> request = Map.of(
                    "baseUrl", provider.getBaseUrl(),
                    "apiKey", valueOrEmpty(cipher.decrypt(provider.getApiKeyEncrypted())),
                    "timeoutSeconds", timeout(provider));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    "http://eh-ai-service/api/ai/dynamic/models", request, Map.class);
            if (response == null || !(response.get("models") instanceof List<?> models)) return List.of();
            return models.stream().map(String::valueOf).toList();
        });
    }

    /** 把一张采样图片提交给内部向量服务，保留文件名以便识别图片格式。 */
    public VisualEmbedding embed(byte[] image, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return filename == null || filename.isBlank() ? "page.jpg" : filename;
            }
        });
        return restTemplate.postForObject("http://eh-ai-service/api/ai/embedding",
                new HttpEntity<>(parts, headers), VisualEmbedding.class);
    }

    /** 读取本地向量模型状态，用于界面展示和任务启动前检查。 */
    public Map<String, Object> embeddingStatus() {
        @SuppressWarnings("unchecked")
        Map<String, Object> status = restTemplate.getForObject(
                "http://eh-ai-service/api/ai/embedding/status", Map.class);
        return status == null ? Map.of() : status;
    }

    /** 整理文本和可选图片，调用指定供应商模型并拒绝空回复。 */
    public String chat(AiProviderConfigEntity provider, String model, String prompt,
                       List<ImagePayload> images) {
        return withPermit(provider, () -> {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("baseUrl", provider.getBaseUrl());
            request.put("apiKey", valueOrEmpty(cipher.decrypt(provider.getApiKeyEncrypted())));
            request.put("model", model);
            request.put("prompt", prompt);
            request.put("temperature", 0.1D);
            request.put("timeoutSeconds", timeout(provider));
            List<Map<String, String>> encoded = new ArrayList<>();
            if (images != null) {
                for (ImagePayload image : images) {
                    encoded.add(Map.of("mimeType", image.mimeType(),
                            "base64", Base64.getEncoder().encodeToString(image.bytes())));
                }
            }
            request.put("images", encoded);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    "http://eh-ai-service/api/ai/dynamic/chat", request, Map.class);
            if (response == null || response.get("content") == null) {
                throw new IllegalStateException("模型返回了空内容");
            }
            return String.valueOf(response.get("content"));
        });
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 先申请供应商并发名额，结束后无论成功失败都归还，避免单个服务被请求压满。 */
    private <T> T withPermit(AiProviderConfigEntity provider, Supplier<T> action) {
        int permits = Math.max(1, Math.min(32,
                provider.getMaxConcurrency() == null ? 2 : provider.getMaxConcurrency()));
        String key = provider.getId() + ":" + permits;
        Semaphore semaphore = providerLimits.computeIfAbsent(key, ignored -> new Semaphore(permits));
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeout(provider), TimeUnit.SECONDS);
            if (!acquired) throw new IllegalStateException("等待模型供应商并发配额超时");
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待模型供应商时任务被中断", exception);
        } finally {
            if (acquired) semaphore.release();
        }
    }

    private static int timeout(AiProviderConfigEntity provider) {
        return Math.max(5, Math.min(600,
                provider.getRequestTimeoutSeconds() == null ? 120 : provider.getRequestTimeoutSeconds()));
    }

    /** 一张待发送给模型的图片及实际格式，避免请求构造阶段丢失内容类型。 */
    public record ImagePayload(String mimeType, byte[] bytes) {
    }
}
