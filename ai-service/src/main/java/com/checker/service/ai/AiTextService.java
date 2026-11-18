package com.checker.service.ai;

import com.checker.manager.ai.DefaultAiManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组织摘要与标签翻译提示词，并把模型文本还原为业务结果。 */
@Service
public class AiTextService {
    private static final Logger log = LoggerFactory.getLogger(AiTextService.class);
    private final DefaultAiManager model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiTextService(DefaultAiManager model) {
        this.model = model;
    }

    public String generateSummary(Map<String, Object> request) {
        return summary(request.getOrDefault("title", "未知"), request.getOrDefault("tags", "无"));
    }

    public String generateGallerySummary(String title, List<String> tags) {
        return summary(title == null || title.isBlank() ? "未知" : title,
                tags == null || tags.isEmpty() ? "无" : String.join(", ", tags));
    }

    private String summary(Object title, Object tags) {
        String prompt = "你是一个专门为漫画编写剧情概要的助手。请为画廊生成150字左右的中文简介。标题: {title}, 标签: {tags}";
        PromptTemplate template = new PromptTemplate(prompt);
        template.add("title", title);
        template.add("tags", tags);
        return model.call(template.create());
    }

    public Map<String, String> batchTranslateTags(Map<String, Object> request) {
        Object tagsObj = request.get("tags");
        List<String> tags = new ArrayList<>();
        if (tagsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) tags.add(String.valueOf(item));
            }
        }
        if (tags.isEmpty()) return Map.of();

        String joined = String.join("\n", tags);
        String prompt = "你是 EHentai 标签翻译助手。请将以下每一行标签翻译为简体中文，"
                + "保持「命名空间:标签名」的格式（命名空间与标签名均翻译为中文），"
                + "每行输出对应输入一行，不要输出任何解释或编号：\n" + joined;
        return parseLinesToMap(tags, model.call(prompt));
    }

    private Map<String, String> parseLinesToMap(List<String> tags, String output) {
        Map<String, String> result = new LinkedHashMap<>();
        if (output == null || output.isBlank()) return result;
        List<String> lines = output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (lines.size() == tags.size()) {
            for (int i = 0; i < tags.size(); i++) result.put(tags.get(i), lines.get(i));
            return result;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = objectMapper.readValue(output, Map.class);
            result.putAll(parsed);
        } catch (Exception failure) {
            log.warn("批量翻译输出解析失败，行数 {} != {}，返回空映射", lines.size(), tags.size());
        }
        return result;
    }
}
