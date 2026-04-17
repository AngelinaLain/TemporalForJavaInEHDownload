package com.checker.controllers;

import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
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
        } catch (RestClientException | org.springframework.web.reactive.function.client.WebClientRequestException e) {
            // 透传 503 异常，通知主服务挂起
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("GPU节点离线或未响应");
        }
    }
}
