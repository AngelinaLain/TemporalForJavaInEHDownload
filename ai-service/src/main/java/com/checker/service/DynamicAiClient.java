package com.checker.service;

import com.checker.dto.DynamicAiRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

@Service
public class DynamicAiClient {
    private static final int MAX_IMAGES = 8;
    private static final int MAX_BASE64_CHARS = 28_000_000;
    private final RestClient.Builder restClientBuilder;

    public DynamicAiClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public List<String> models(String baseUrl, String apiKey, Integer timeoutSeconds) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = request(baseUrl, apiKey, timeoutSeconds).get()
                .uri(endpoint(baseUrl, "models"))
                .retrieve().body(Map.class);
        if (response == null || !(response.get("data") instanceof List<?> data)) return List.of();
        return data.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> item.get("id"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .distinct().sorted().toList();
    }

    public String chat(DynamicAiRequest input) {
        if (input == null || input.model() == null || input.model().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (input.prompt() == null || input.prompt().isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", input.model());
        body.put("temperature", input.temperature() == null ? 0.1D : input.temperature());
        body.put("messages", List.of(Map.of("role", "user", "content", content(input))));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = request(input.baseUrl(), input.apiKey(), input.timeoutSeconds()).post()
                .uri(endpoint(input.baseUrl(), "chat/completions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class);
        return extractContent(response);
    }

    private Object content(DynamicAiRequest input) {
        List<DynamicAiRequest.ImageInput> images = input.images() == null ? List.of() : input.images();
        if (images.isEmpty()) return input.prompt();
        if (images.size() > MAX_IMAGES) throw new IllegalArgumentException("单次视觉请求最多允许 8 张图片");
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", input.prompt()));
        for (DynamicAiRequest.ImageInput image : images) {
            if (image == null || image.base64() == null || image.base64().isBlank()) continue;
            if (image.base64().length() > MAX_BASE64_CHARS) throw new IllegalArgumentException("图片大小超过限制");
            String mime = image.mimeType() == null || image.mimeType().isBlank()
                    ? "image/jpeg" : image.mimeType();
            if (!mime.startsWith("image/")) throw new IllegalArgumentException("不支持的图片类型");
            parts.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", "data:" + mime + ";base64," + image.base64())));
        }
        return parts;
    }

    private RestClient request(String baseUrl, String apiKey, Integer timeoutSeconds) {
        validateBaseUrl(baseUrl);
        int seconds = Math.max(5, Math.min(600, timeoutSeconds == null ? 120 : timeoutSeconds));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(30, seconds)));
        requestFactory.setReadTimeout(Duration.ofSeconds(seconds));
        RestClient.Builder builder = restClientBuilder.clone().requestFactory(requestFactory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static URI endpoint(String baseUrl, String resource) {
        validateBaseUrl(baseUrl);
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (!normalized.toLowerCase().endsWith("/v1")) normalized += "/v1";
        return URI.create(normalized + "/" + resource);
    }

    private static void validateBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Base URL 不能为空");
        URI uri = URI.create(value.trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Base URL 必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private static String extractContent(Map<String, Object> response) {
        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("模型没有返回 choices");
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice) || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IllegalStateException("模型响应格式不正确");
        }
        Object content = message.get("content");
        if (content == null || String.valueOf(content).isBlank()) {
            throw new IllegalStateException("模型返回了空内容");
        }
        return String.valueOf(content);
    }
}
