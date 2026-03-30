package com.checker.controllers;

import com.checker.common.Constants;
import com.checker.common.Result;
import com.checker.dto.SearchOptions;
import com.checker.temporalServices.activities.NotificationActivity;
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

/**
 * EHentai 自动化工作流 REST 控制器
 */
@RestController
@RequestMapping("/api/temporal/eh")
public class EHAutomationController {

    @Autowired
    private WorkflowClient workflowClient;

    /**
     * 启动 EHentai 自动化工作流：按搜索条件抓取画廊并推送下载
     *
     * @param searchOptions 搜索参数（关键词、分类、评分等）
     * @return 包含 workflowId 和 runId 的响应
     */
    @PostMapping("/start")
    public Result<Map<String, String>> startWorkflow(@Valid @RequestBody SearchOptions searchOptions) {
        String workflowId = "eh-auto-" + UUID.randomUUID();

        EHAutomationWorkflow workflow = workflowClient.newWorkflowStub(
                EHAutomationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(Constants.TASK_QUEUE)
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
                        .setTaskQueue(Constants.TASK_QUEUE)
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

    @Autowired
    private NotificationActivity notificationActivity;
    /**
     * 测试/Debug 专用：直接测试 Microsoft Graph API 发送邮件功能
     */
    @PostMapping("/test-email")
    public Result<Map<String, String>> testEmail(@RequestBody(required = false) Map<String, String> payload) {
        String subject = "EHentai 自动化 - 邮件测试";
        String content = "这是一封测试邮件。如果您收到此邮件，说明您的 Microsoft E5 Graph API 凭证配置完全正确！";

        // 如果通过 Postman 传了自定义内容，就使用自定义内容
        if (payload != null) {
            subject = payload.getOrDefault("subject", subject);
            content = payload.getOrDefault("content", content);
        }

        // 直接调用 Activity 的发邮件方法 (脱离 Temporal 框架直接执行)
        notificationActivity.sendEmailAlert(subject, content);

        return Result.success(Map.of(
                "message", "发送邮件指令已执行，请查看控制台日志以及您的管理员邮箱接收情况。"
        ));
    }
}