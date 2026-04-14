package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流运行时配置：轮询间隔、并发度、重试次数等可在 application.yaml 中调整，无需重新编译
 */
@Data
@Component
@ConfigurationProperties(prefix = "eh-config.workflow")
public class EhWorkflowConfig {
    /** 子工作流最大并发数（受 EHentai 限制，建议 ≤ 3） */
    private int maxConcurrency = 2;
    /** Komga 入库轮询最大次数 */
    private int komgaImportMaxRetries = 20;
    /** Komga 入库轮询间隔（秒） */
    private int komgaImportPollIntervalSeconds = 15;
    /** 群晖下载状态轮询间隔（分钟） */
    private int downloadPollIntervalMinutes = 5;
    /** 单本画廊下载完成后的冷却等待（秒） */
    private int downloadCooldownSeconds = 10;
}
