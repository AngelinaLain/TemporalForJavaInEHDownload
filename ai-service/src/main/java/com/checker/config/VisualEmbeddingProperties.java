package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.embedding")
public class VisualEmbeddingProperties {
    private boolean enabled = false;
    private String modelPath = "/models/dinov2-small-ONNX_int8.onnx";
    private String modelName = "dinov2-small-int8";
    private String modelVersion = "dinov2-small-int8-v1";
    private int inputSize = 224;
    private long maxImagePixels = 40_000_000L;
    private int intraOpThreads = 0;
}
