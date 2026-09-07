package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.temporalServices.activities.AiActivity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * AI 推理的 Temporal Worker。
 *
 * <p>它必须运行在 eh-ai-service：该服务拥有模型/GPU 端点配置。main-service 仅创建
 * Activity Stub，因此 GPU 节点的运行时异常和重试日志会归属到本服务。</p>
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.AI_TASK_QUEUE)
public class AiActivityImpl implements AiActivity {

    private static final String GPU_UNAVAILABLE = "AI_NODE_UNAVAILABLE";

    private final ChatClient chatClient;

    @Autowired
    public AiActivityImpl(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String generateGallerySummary(String title, List<String> tags) {
        String prompt = "你是一个专门为漫画编写剧情概要的助手。请为画廊生成150字左右的中文简介。标题: {title}, 标签: {tags}";
        PromptTemplate template = new PromptTemplate(prompt);
        template.add("title", title == null || title.isBlank() ? "未知" : title);
        template.add("tags", tags == null || tags.isEmpty() ? "无" : String.join(", ", tags));
        try {
            String summary = chatClient.call(template.create()).getResult().getOutput().getContent();
            if (summary == null || summary.isBlank()) {
                throw ApplicationFailure.newFailure("AI returned an empty summary", "AI_EMPTY_RESPONSE");
            }
            return summary;
        } catch (RestClientException | org.springframework.web.reactive.function.client.WebClientRequestException |
                 TransientAiException failure) {
            log.warn("GPU 节点不可用；由 eh-ai-service 的 Temporal Worker 重试: {}", failure.getMessage());
            throw ApplicationFailure.newFailure("GPU节点离线或未响应", GPU_UNAVAILABLE);
        }
    }
}
