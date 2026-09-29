package com.checker.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.dto.AiProviderRequest;
import com.checker.dto.AiProviderView;
import com.checker.dto.AiUseCaseRequest;
import com.checker.entity.AiPromptVersionEntity;
import com.checker.entity.AiProviderConfigEntity;
import com.checker.entity.AiUseCaseConfigEntity;
import com.checker.mapper.AiPromptVersionMapper;
import com.checker.mapper.AiProviderConfigMapper;
import com.checker.mapper.AiUseCaseConfigMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiConfigurationService {
    public static final String VISUAL_REVIEW = "VISUAL_DUPLICATE_REVIEW";
    private static final Set<String> MODES = Set.of("EMBEDDING_ONLY", "EMBEDDING_TEXT_LLM", "VISION_LLM");
    private static final Map<String, String> DEFAULT_PROMPTS = Map.of(
            "SUMMARY", "你是一个专门为漫画编写剧情概要的助手。根据标题和标签生成约150字的中文简介，不要虚构未提供的事实。",
            "TAG_TRANSLATION", "你是 EHentai 标签翻译助手。逐行翻译为简体中文并保持命名空间格式，不要输出解释或编号。",
            VISUAL_REVIEW, "你是视觉重复内容复核助手。只能依据输入的数值证据判断，不得推测未提供的图片内容。严格输出 JSON，字段为 decision、confidence、reason、requiresHumanReview；decision 只能是 SAME_CONTENT、POSSIBLE_VARIANT、DIFFERENT_CONTENT 或 INSUFFICIENT_EVIDENCE。"
    );

    private final AiProviderConfigMapper providerMapper;
    private final AiUseCaseConfigMapper useCaseMapper;
    private final AiPromptVersionMapper promptMapper;
    private final AiProviderSecretCipher cipher;

    public AiConfigurationService(AiProviderConfigMapper providerMapper,
                                  AiUseCaseConfigMapper useCaseMapper,
                                  AiPromptVersionMapper promptMapper,
                                  AiProviderSecretCipher cipher) {
        this.providerMapper = providerMapper;
        this.useCaseMapper = useCaseMapper;
        this.promptMapper = promptMapper;
        this.cipher = cipher;
    }

    public List<AiProviderView> providers() {
        return providerMapper.selectList(new QueryWrapper<AiProviderConfigEntity>().orderByAsc("name"))
                .stream().map(AiProviderView::from).toList();
    }

    public AiProviderConfigEntity requireProvider(long id) {
        AiProviderConfigEntity provider = providerMapper.selectById(id);
        if (provider == null) throw new IllegalArgumentException("AI 供应商不存在");
        return provider;
    }

    public AiUseCaseConfigEntity requireUseCase(String useCase) {
        ensureDefaults();
        AiUseCaseConfigEntity config = useCaseMapper.selectOne(
                new QueryWrapper<AiUseCaseConfigEntity>().eq("use_case", normalizeUseCase(useCase)));
        if (config == null) throw new IllegalArgumentException("AI 用例不存在");
        return config;
    }

    public AiPromptVersionEntity requirePrompt(Long id) {
        AiPromptVersionEntity prompt = id == null ? null : promptMapper.selectById(id);
        if (prompt == null) throw new IllegalArgumentException("提示词版本不存在");
        return prompt;
    }

    @Transactional
    public AiProviderView create(AiProviderRequest request) {
        AiProviderConfigEntity entity = new AiProviderConfigEntity();
        applyProvider(entity, request, false);
        try {
            providerMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("供应商名称已存在");
        }
        return AiProviderView.from(entity);
    }

    @Transactional
    public AiProviderView update(long id, AiProviderRequest request) {
        AiProviderConfigEntity entity = requireProvider(id);
        applyProvider(entity, request, true);
        try {
            providerMapper.updateById(entity);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("供应商名称已存在");
        }
        return AiProviderView.from(entity);
    }

    @Transactional
    public void delete(long id) {
        if (providerMapper.deleteById(id) == 0) throw new IllegalArgumentException("AI 供应商不存在");
    }

    public List<Map<String, Object>> useCases() {
        ensureDefaults();
        return useCaseMapper.selectList(new QueryWrapper<AiUseCaseConfigEntity>().orderByAsc("use_case"))
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> updateUseCase(String useCase, AiUseCaseRequest request) {
        String normalized = normalizeUseCase(useCase);
        if (!MODES.contains(request.analysisMode())) throw new IllegalArgumentException("不支持的分析模式");
        if (request.ambiguousMinSimilarity() != null && request.highSimilarity() != null
                && request.ambiguousMinSimilarity().compareTo(request.highSimilarity()) >= 0) {
            throw new IllegalArgumentException("模糊区间下限必须小于高相似阈值");
        }
        AiProviderConfigEntity provider = request.providerId() == null ? null : requireProvider(request.providerId());
        boolean wantsImages = Boolean.TRUE.equals(request.allowImageTransmission());
        if (wantsImages && !"VISION_LLM".equals(request.analysisMode())) {
            throw new IllegalArgumentException("只有视觉 LLM 模式可以发送图片");
        }
        if (wantsImages && (provider == null || !Boolean.TRUE.equals(provider.getAllowVisualInput()))) {
            throw new IllegalArgumentException("所选供应商未允许视觉输入");
        }
        AiUseCaseConfigEntity config = findOrCreate(normalized);
        config.setProviderId(request.providerId());
        config.setModel(blankToNull(request.model()));
        config.setAnalysisMode(request.analysisMode());
        config.setAllowImageTransmission(wantsImages);
        config.setIncludeMetadata(Boolean.TRUE.equals(request.includeMetadata()));
        config.setSamplePageCount(request.samplePageCount() == null ? 4 : request.samplePageCount());
        config.setAmbiguousMinSimilarity(request.ambiguousMinSimilarity() == null
                ? new BigDecimal("0.86000") : request.ambiguousMinSimilarity());
        config.setHighSimilarity(request.highSimilarity() == null
                ? new BigDecimal("0.94000") : request.highSimilarity());
        String prompt = blankToNull(request.prompt());
        if (prompt != null) config.setPromptVersionId(createPrompt(normalized, prompt, false).getId());
        useCaseMapper.updateById(config);
        return view(config);
    }

    public String defaultPrompt(String useCase) {
        String prompt = DEFAULT_PROMPTS.get(normalizeUseCase(useCase));
        if (prompt == null) throw new IllegalArgumentException("不支持的 AI 用例");
        return prompt;
    }

    @Transactional
    public void ensureDefaults() {
        for (String useCase : DEFAULT_PROMPTS.keySet()) findOrCreate(useCase);
    }

    private AiUseCaseConfigEntity findOrCreate(String useCase) {
        AiUseCaseConfigEntity existing = useCaseMapper.selectOne(
                new QueryWrapper<AiUseCaseConfigEntity>().eq("use_case", useCase));
        if (existing != null) return existing;
        AiPromptVersionEntity prompt = createPrompt(useCase, DEFAULT_PROMPTS.get(useCase), true);
        AiUseCaseConfigEntity config = new AiUseCaseConfigEntity();
        config.setUseCase(useCase);
        config.setAnalysisMode("EMBEDDING_ONLY");
        config.setAllowImageTransmission(false);
        config.setIncludeMetadata(false);
        config.setSamplePageCount(4);
        config.setAmbiguousMinSimilarity(new BigDecimal("0.86000"));
        config.setHighSimilarity(new BigDecimal("0.94000"));
        config.setPromptVersionId(prompt.getId());
        useCaseMapper.insert(config);
        return config;
    }

    private AiPromptVersionEntity createPrompt(String useCase, String prompt, boolean isDefault) {
        QueryWrapper<AiPromptVersionEntity> query = new QueryWrapper<>();
        query.eq("use_case", useCase).orderByDesc("version").last("LIMIT 1");
        AiPromptVersionEntity latest = promptMapper.selectOne(query);
        if (latest != null && latest.getPrompt().equals(prompt)) return latest;
        AiPromptVersionEntity entity = new AiPromptVersionEntity();
        entity.setUseCase(useCase);
        entity.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        entity.setPrompt(prompt);
        entity.setIsDefault(isDefault);
        promptMapper.insert(entity);
        return entity;
    }

    private Map<String, Object> view(AiUseCaseConfigEntity config) {
        AiPromptVersionEntity prompt = config.getPromptVersionId() == null
                ? null : promptMapper.selectById(config.getPromptVersionId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("useCase", config.getUseCase());
        view.put("providerId", config.getProviderId());
        view.put("model", config.getModel());
        view.put("analysisMode", config.getAnalysisMode());
        view.put("allowImageTransmission", config.getAllowImageTransmission());
        view.put("includeMetadata", config.getIncludeMetadata());
        view.put("samplePageCount", config.getSamplePageCount());
        view.put("ambiguousMinSimilarity", config.getAmbiguousMinSimilarity());
        view.put("highSimilarity", config.getHighSimilarity());
        view.put("promptVersionId", config.getPromptVersionId());
        view.put("promptVersion", prompt == null ? null : prompt.getVersion());
        view.put("prompt", prompt == null ? defaultPrompt(config.getUseCase()) : prompt.getPrompt());
        return view;
    }

    private void applyProvider(AiProviderConfigEntity entity, AiProviderRequest request, boolean updating) {
        validateBaseUrl(request.baseUrl());
        String scope = request.scope().trim().toUpperCase();
        if (!Set.of("LOCAL", "REMOTE").contains(scope)) throw new IllegalArgumentException("供应商范围必须是 LOCAL 或 REMOTE");
        entity.setName(request.name().trim());
        entity.setScope(scope);
        entity.setProtocol("OPENAI_COMPATIBLE");
        entity.setBaseUrl(request.baseUrl().trim().replaceAll("/+$", ""));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKeyEncrypted(cipher.encrypt(request.apiKey().trim()));
        } else if (!updating) {
            entity.setApiKeyEncrypted(null);
        }
        entity.setDefaultModel(blankToNull(request.defaultModel()));
        entity.setAllowTextMetadata(!Boolean.FALSE.equals(request.allowTextMetadata()));
        entity.setAllowVisualInput(Boolean.TRUE.equals(request.allowVisualInput()));
        entity.setRequestTimeoutSeconds(request.requestTimeoutSeconds() == null ? 120 : request.requestTimeoutSeconds());
        entity.setMaxConcurrency(request.maxConcurrency() == null ? 2 : request.maxConcurrency());
        entity.setEnabled(!Boolean.FALSE.equals(request.enabled()));
    }

    private static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Base URL 必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private static String normalizeUseCase(String value) {
        if (value == null) throw new IllegalArgumentException("AI 用例不能为空");
        return value.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
