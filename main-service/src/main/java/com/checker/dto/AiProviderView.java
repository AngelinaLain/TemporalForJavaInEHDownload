package com.checker.dto;

import com.checker.entity.AiProviderConfigEntity;

/**
 * 向前端返回模型供应商的可展示信息，只标记是否配置密钥，不返回密钥内容。
 */
public record AiProviderView(Long id, String name, String scope, String protocol, String baseUrl,
                             boolean hasApiKey, String defaultModel, Boolean allowTextMetadata,
                             Boolean allowVisualInput, Integer requestTimeoutSeconds,
                             Integer maxConcurrency, Boolean enabled) {
    /**
     * 把数据库中的供应商配置转成可安全展示的结果，隐藏加密密钥，只保留是否已配置的标记。
     */
    public static AiProviderView from(AiProviderConfigEntity entity) {
        return new AiProviderView(entity.getId(), entity.getName(), entity.getScope(), entity.getProtocol(),
                entity.getBaseUrl(), entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank(),
                entity.getDefaultModel(), entity.getAllowTextMetadata(), entity.getAllowVisualInput(),
                entity.getRequestTimeoutSeconds(), entity.getMaxConcurrency(), entity.getEnabled());
    }
}
