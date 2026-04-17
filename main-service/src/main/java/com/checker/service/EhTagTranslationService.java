package com.checker.service;

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
