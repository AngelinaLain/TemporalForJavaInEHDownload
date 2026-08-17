package com.checker.service;

import java.util.List;
import java.util.Map;

/**
 * EhTagTranslation 标签翻译服务
 * 数据源: https://github.com/EhTagTranslation/DatabaseReleases
 */
public interface EhTagTranslationService {

    /**
     * 翻译单个标签（如 "female:stockings" → "女性:丝袜"）
     * @param tag 英文标签，格式 "namespace:tagname" 或 "tagname"
     * @return 中文翻译，找不到时返回原始标签
     */
    String translate(String tag);

    /**
     * 批量翻译标签：
     * 先走本地词典与 Redis 共享缓存，未命中的部分合并为单个 AI Prompt 批量翻译
     * （AI 熔断/失败时静默降级，只返回本地命中结果）。
     *
     * @param tags 待翻译标签列表
     * @return 原文 → 译文 映射
     */
    Map<String, String> translateBatch(List<String> tags);

    /**
     * 获取完整的翻译映射表（namespace:tag → 中文名）
     */
    Map<String, String> getTranslationMap();

    /**
     * 获取命名空间翻译（如 "female" → "女性"）
     */
    String translateNamespace(String namespace);

    /**
     * 手动刷新翻译缓存
     */
    void refreshCache();

    /**
     * 获取标签描述（intro 字段），如 "female:stockings" → "长筒袜，一种紧贴腿部的袜子..."
     */
    String getDescription(String tag);

    /**
     * 获取完整的标签描述映射表（namespace:tag → 描述文本）
     */
    Map<String, String> getDescriptionMap();
}
