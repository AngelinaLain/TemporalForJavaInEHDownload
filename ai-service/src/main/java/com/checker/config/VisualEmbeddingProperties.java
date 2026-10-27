package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 保存本地视觉模型的开关、文件位置、输入尺寸和资源限制。 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.embedding")
public class VisualEmbeddingProperties {
    /** 默认不开启，需要模型文件准备好后显式启用。 */
    private boolean enabled = false;
    private String modelPath = "/models/dinov2-small-ONNX_int8.onnx";
    private String modelName = "dinov2-small-int8";
    private String modelVersion = "dinov2-small-int8-v1";
    /** 输入图片会统一缩放为此边长的正方形。 */
    private int inputSize = 224;
    /** 解码后允许处理的最大图片像素数。 */
    private long maxImagePixels = 40_000_000L;
    /** 单次推理的线程数；零表示使用推理运行时默认设置。 */
    private int intraOpThreads = 0;
}
