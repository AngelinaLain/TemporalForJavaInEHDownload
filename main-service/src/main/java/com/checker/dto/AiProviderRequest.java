package com.checker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 接收用户新增或修改模型供应商时填写的地址、密钥、权限和并发设置。
 */
public record AiProviderRequest(
        @NotBlank String name,
        @NotBlank String scope,
        @NotBlank String baseUrl,
        String apiKey,
        String defaultModel,
        Boolean allowTextMetadata,
        Boolean allowVisualInput,
        @Min(5) @Max(600) Integer requestTimeoutSeconds,
        @Min(1) @Max(32) Integer maxConcurrency,
        Boolean enabled) {
}
