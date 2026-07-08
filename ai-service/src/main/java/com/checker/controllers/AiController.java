package com.checker.controllers;

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

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Autowired
    private ChatClient chatClient;

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
}
