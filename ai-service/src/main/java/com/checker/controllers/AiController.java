package com.checker.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Autowired
    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/generate-summary")
    public ResponseEntity<String> generateSummary(@RequestBody Map<String, Object> request) {
        String prompt = "你是一个专门为漫画编写剧情概要的助手。请为画廊生成150字左右的中文简介。标题: {title}, 标签: {tags}";
        PromptTemplate template = new PromptTemplate(prompt);
        template.add("title", request.getOrDefault("title", "未知"));
        template.add("tags", request.getOrDefault("tags", "无"));

        try {
            String response = chatClient.call(template.create()).getResult().getOutput().getContent();
            return ResponseEntity.ok(response);
        } catch (RestClientException | org.springframework.web.reactive.function.client.WebClientRequestException |
                 TransientAiException e) {
            // 捕获网络异常及 Spring AI 包装的远端 500 异常
            log.warn("GPU节点调用失败 (网络或远端服务异常): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("GPU节点离线或未响应");
        } catch (Exception e) {
            // 兜底捕获其他未知异常，防止穿透
            log.error("AI 摘要生成发生未知内部错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("内部服务错误");
        }
    }

    /**
     * 批量标签翻译：将多个 Tag 合并为单个 Prompt 批量请求，节约 Token 并降低延迟。
     * 请求体: {"tags": ["artist:aaa", "language:chinese", ...]}
     * 响应:   {"artist:aaa": "艺术家:某某", ...}
     */
    @PostMapping("/batch-translate-tags")
    public ResponseEntity<Map<String, String>> batchTranslateTags(@RequestBody Map<String, Object> request) {
        Object tagsObj = request.get("tags");
        List<String> tags = new ArrayList<>();
        if (tagsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) tags.add(String.valueOf(item));
            }
        }
        if (tags.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }

        String joined = String.join("\n", tags);
        String prompt = "你是 EHentai 标签翻译助手。请将以下每一行标签翻译为简体中文，"
                + "保持「命名空间:标签名」的格式（命名空间与标签名均翻译为中文），"
                + "每行输出对应输入一行，不要输出任何解释或编号：\n" + joined;

        try {
            String output = chatClient.call(prompt);
            Map<String, String> result = parseLinesToMap(tags, output);
            return ResponseEntity.ok(result);
        } catch (RestClientException | org.springframework.web.reactive.function.client.WebClientRequestException |
                 TransientAiException e) {
            log.warn("批量标签翻译调用失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of());
        } catch (Exception e) {
            log.error("批量标签翻译发生未知内部错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of());
        }
    }

    /**
     * 将模型输出按行对应回输入标签；行数不匹配时尝试按 JSON 解析，最终兜底返回空映射。
     */
    private Map<String, String> parseLinesToMap(List<String> tags, String output) {
        Map<String, String> result = new LinkedHashMap<>();
        if (output == null || output.isBlank()) return result;

        List<String> lines = output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.size() == tags.size()) {
            for (int i = 0; i < tags.size(); i++) {
                result.put(tags.get(i), lines.get(i));
            }
            return result;
        }

        // 模型可能返回 JSON，尝试兜底解析
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = objectMapper.readValue(output, Map.class);
            result.putAll(parsed);
        } catch (Exception e) {
            log.warn("批量翻译输出解析失败，行数 {} != {}，返回空映射", lines.size(), tags.size());
        }
        return result;
    }
}
