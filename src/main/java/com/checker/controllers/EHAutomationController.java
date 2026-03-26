package com.checker.controllers;

import com.checker.common.Result;
import com.checker.dto.SearchOptions;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
import com.checker.temporalServices.workflows.RetryFailedDownloadWorkflow; // 记得加上这个导入
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/temporal/eh")
public class EHAutomationController {

    @Autowired
    private WorkflowClient workflowClient;

    @PostMapping("/start")
    public Result<Map<String, String>> startWorkflow(@Valid @RequestBody SearchOptions searchOptions) {
        String workflowId = "eh-auto-" + UUID.randomUUID();

        EHAutomationWorkflow workflow = workflowClient.newWorkflowStub(
                EHAutomationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("EHDownloadTaskQueue")
                        .setWorkflowId(workflowId)
                        .build()
        );

        // 填充默认值
        if (searchOptions.getFCats() == null) searchOptions.setFCats(0);
        if (searchOptions.getMinimumRating() == null) searchOptions.setMinimumRating(1);
        if (searchOptions.getSearchExpungedGalleries() == null) searchOptions.setSearchExpungedGalleries(false);
        if (searchOptions.getShowOnlyWithTorrents() == null) searchOptions.setShowOnlyWithTorrents(false);
        if (searchOptions.getDisableLanguageFilter() == null) searchOptions.setDisableLanguageFilter(false);
        if (searchOptions.getDisableUploaderFilter() == null) searchOptions.setDisableUploaderFilter(false);
        if (searchOptions.getDisableTagsFilter() == null) searchOptions.setDisableTagsFilter(false);

        WorkflowExecution execution = WorkflowClient.start(workflow::executeAutomation, searchOptions);
        return Result.success(Map.of(
                "workflowId", execution.getWorkflowId(),
                "runId", execution.getRunId()
        ));
    }

    /**
     * 触发：重试数据库中所有标记为“下载失败”的画廊
     */
    @PostMapping("/retry-failed")
    public Result<Map<String, String>> retryFailedWorkflow() {
        // 使用不同的前缀以区分不同类型的工作流
        String workflowId = "eh-retry-" + UUID.randomUUID();

        RetryFailedDownloadWorkflow workflow = workflowClient.newWorkflowStub(
                RetryFailedDownloadWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("EHDownloadTaskQueue")
                        .setWorkflowId(workflowId)
                        .build()
        );

        // 异步启动重试工作流（无入参）
        WorkflowExecution execution = WorkflowClient.start(workflow::retryFailedTasks);

        return Result.success(Map.of(
                "workflowId", execution.getWorkflowId(),
                "runId", execution.getRunId()
        ));
    }
}