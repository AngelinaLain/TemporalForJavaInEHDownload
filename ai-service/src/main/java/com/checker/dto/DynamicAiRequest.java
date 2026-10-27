package com.checker.dto;

import java.util.List;

/** 一次动态模型调用的参数，包含目标服务、模型、提示词和可选图片。 */
public record DynamicAiRequest(String baseUrl, String apiKey, String model, String prompt,
                               List<ImageInput> images, Double temperature, Integer timeoutSeconds) {
    /** 将图片类型和编码内容一起传递，供视觉模型请求组装使用。 */
    public record ImageInput(String mimeType, String base64) {
    }
}
