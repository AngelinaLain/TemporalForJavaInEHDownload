package com.checker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

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
