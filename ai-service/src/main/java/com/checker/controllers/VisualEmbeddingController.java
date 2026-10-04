package com.checker.controllers;

import com.checker.dto.VisualEmbedding;
import com.checker.service.VisualEmbeddingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/embedding")
public class VisualEmbeddingController {
    private final VisualEmbeddingService embeddingService;

    public VisualEmbeddingController(VisualEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return embeddingService.status();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> embed(@RequestPart("image") MultipartFile image) {
        try {
            VisualEmbedding embedding = embeddingService.embed(image.getBytes());
            return ResponseEntity.ok(embedding);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(503).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of("error", "视觉向量生成失败"));
        }
    }
}
