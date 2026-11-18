package com.checker.manager.ai;

import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** 封装应用默认模型的供应商调用，业务层不依赖响应对象结构。 */
@Component
public class DefaultAiManager {
    private final ChatClient chatClient;

    public DefaultAiManager(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String call(Prompt prompt) {
        return chatClient.call(prompt).getResult().getOutput().getContent();
    }

    public String call(String prompt) {
        return chatClient.call(prompt);
    }
}
