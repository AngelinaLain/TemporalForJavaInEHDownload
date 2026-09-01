package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工作流运行时设置 DTO — 从 EhWorkflowConfig 读取后通过 Activity 传递给工作流，
 * 使 Temporal 工作流可获取 application.yaml 中的运行时配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowSettings implements Serializable {
    @Builder.Default
    private int maxConcurrency = 2;
    @Builder.Default
    private int komgaImportMaxRetries = 20;
    @Builder.Default
    private int komgaImportPollIntervalSeconds = 15;
    @Builder.Default
    private int downloadPollIntervalMinutes = 5;
    @Builder.Default
    private int downloadCooldownSeconds = 10;
    @Builder.Default
    private String downloadMode = "local";
}
