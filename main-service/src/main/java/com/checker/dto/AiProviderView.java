package com.checker.dto;

import com.checker.entity.AiProviderConfigEntity;

public record AiProviderView(Long id, String name, String scope, String protocol, String baseUrl,
                             boolean hasApiKey, String defaultModel, Boolean allowTextMetadata,
                             Boolean allowVisualInput, Integer requestTimeoutSeconds,
                             Integer maxConcurrency, Boolean enabled) {
    public static AiProviderView from(AiProviderConfigEntity entity) {
        return new AiProviderView(entity.getId(), entity.getName(), entity.getScope(), entity.getProtocol(),
                entity.getBaseUrl(), entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank(),
                entity.getDefaultModel(), entity.getAllowTextMetadata(), entity.getAllowVisualInput(),
                entity.getRequestTimeoutSeconds(), entity.getMaxConcurrency(), entity.getEnabled());
    }
}
