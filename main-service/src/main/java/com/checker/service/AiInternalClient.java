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

@Service
public class AiInternalClient {
    private final RestTemplate restTemplate;
    private final AiProviderSecretCipher cipher;
    private final Map<String, Semaphore> providerLimits = new ConcurrentHashMap<>();

    public AiInternalClient(RestTemplate restTemplate, AiProviderSecretCipher cipher) {
        this.restTemplate = restTemplate;
        this.cipher = cipher;
    }

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

    public Map<String, Object> embeddingStatus() {
        @SuppressWarnings("unchecked")
        Map<String, Object> status = restTemplate.getForObject(
                "http://eh-ai-service/api/ai/embedding/status", Map.class);
        return status == null ? Map.of() : status;
    }

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

    public record ImagePayload(String mimeType, byte[] bytes) {
    }
}
