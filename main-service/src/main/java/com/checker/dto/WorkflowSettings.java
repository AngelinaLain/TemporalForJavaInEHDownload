package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 把当前下载并发数、轮询间隔和重试次数作为配置快照传给工作流。
 * 工作流通过数据库活动读取这份配置，避免在历史重放时直接访问会变化的外部配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowSettings implements Serializable {
    @Builder.Default
    private int maxConcurrency = 2;
    @Builder.Default
    private int komgaImportMaxRetries = 40;
    @Builder.Default
    private int komgaImportPollIntervalSeconds = 15;
    @Builder.Default
    private int downloadPollIntervalMinutes = 5;
    @Builder.Default
    private int downloadCooldownSeconds = 10;
    @Builder.Default
    private String downloadMode = "local";
}
