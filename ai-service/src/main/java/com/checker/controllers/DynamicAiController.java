package com.checker.controllers;

import com.checker.dto.DynamicAiRequest;
import com.checker.service.DynamicAiClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/dynamic")
public class DynamicAiController {
    private final DynamicAiClient client;

    public DynamicAiController(DynamicAiClient client) {
        this.client = client;
    }

    @PostMapping("/models")
    public ResponseEntity<?> models(@RequestBody DynamicAiRequest request) {
        try {
            List<String> models = client.models(request.baseUrl(), request.apiKey(), request.timeoutSeconds());
            return ResponseEntity.ok(Map.of("models", models));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.status(503).body(Map.of("error", "无法连接模型服务"));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody DynamicAiRequest request) {
        try {
            return ResponseEntity.ok(Map.of("content", client.chat(request)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.status(503).body(Map.of("error", "模型调用失败"));
        }
    }
}
