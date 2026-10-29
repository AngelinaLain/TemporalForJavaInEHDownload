package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.checker.common.Constants;
import com.checker.common.Result;
import com.checker.dto.KomgaCollectionRequest;
import com.checker.dto.SearchOptions;
import com.checker.service.KomgaCollectionService;
import com.checker.service.KomgaSyncService;
import com.checker.temporalServices.activities.NotificationActivity;
import com.checker.temporalServices.workflows.EHAutomationWorkflow;
import com.checker.temporalServices.workflows.RetryFailedDownloadWorkflow; // 记得加上这个导入
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * 提供采集、失败重试、通知测试和 Komga 元数据维护的操作入口，将耗时工作交给工作流或后台服务。
 */
@RestController
@RequestMapping("/api/temporal/eh")
@Tag(name = "EH 自动化", description = "EHentai 抓取、工作流与批处理任务")
@PreAuthorize("hasRole('ADMIN')")
public class EHAutomationController {

    @Autowired
    private WorkflowClient workflowClient;

    /**
     * 根据用户的检索条件启动自动化主工作流，并返回本次工作流标识。
     */
    @PostMapping("/start")
    public Result<Map<String, String>> startWorkflow(@Valid @RequestBody SearchOptions searchOptions) {
        List<String> galleryUrls = searchOptions.getGalleryUrls() == null ? List.of() : searchOptions.getGalleryUrls().stream()
                .filter(url -> url != null && !url.isBlank()).map(String::trim).distinct().toList();
        if (galleryUrls.isEmpty() && (searchOptions.getKeyword() == null || searchOptions.getKeyword().isBlank())) {
            return Result.error(400, "请输入关键词或至少一个包含 token 的画廊链接");
        }
        if (!galleryUrls.isEmpty()) {
            searchOptions.setGalleryUrls(galleryUrls);
        }
        String workflowId = "eh-auto-" + UUID.randomUUID();
        EHAutomationWorkflow workflow = workflowClient.newWorkflowStub(
                EHAutomationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(Constants.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build()
        );
        // 填充默认值
        if (searchOptions.getFilterCats() == null) searchOptions.setFilterCats(0);
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
     * 启动失败任务重试流程，重新处理符合重试条件的画廊。
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
     * 发送测试通知，帮助检查邮件配置是否可用。
     */
    @PostMapping("/test-email")
    public Result<Map<String, String>> testEmail(@RequestBody(required = false) Map<String, String> payload) {
        String subject = "GalleryImport · 邮件通知测试";
        String content = "这是一封 GalleryImport 通知样式测试邮件。\n成功收到本邮件，表示本次邮件发送链路可用。"
                + "\n正式批次报告将显示任务概况、状态统计、画廊明细及需要处理的事项。";
        if (payload != null) {
            subject = payload.getOrDefault("subject", subject);
            content = payload.getOrDefault("content", content);
        }
        notificationActivity.sendEmailAlert(subject, content);
        return Result.success(Map.of(
                "message", "测试通知已提交后台队列，请查看管理员邮箱；是否成功送达以收件结果和后台日志为准。"
        ));
    }

    @Autowired
    private KomgaCollectionService komgaCollectionService;
    /**
     * 按标签查找系列，并在 Komga 中创建或补充对应合集。
     */
    @PostMapping("/collections/build-by-tags")
    public Result<String> buildCollectionByTags(@RequestBody KomgaCollectionRequest request) {
        if (request.getCollectionName() == null || request.getTags() == null || request.getTags().isEmpty()) {
            return Result.error("合集名称和Tags不能为空");
        }

        komgaCollectionService.processCollectionAssignment(request);

        return Result.success("合集 [" + request.getCollectionName() + "] 构建指令已接收并处理成功");
    }

    @Autowired
    private KomgaSyncService komgaSyncService;

    /**
     * 启动数据库标签向 Komga 的后台同步。
     */
    @PostMapping("/sync-tags")
    public Result<String> syncTagsToKomga() {
        /* 异步执行 */
        komgaSyncService.startTagSync();
        return Result.success("同步任务已在后台启动，请查看控制台日志！");
    }

    /**
     * 启动后台任务，把数据库现有元数据批量同步到已关联的 Komga 书籍。
     */
    @PostMapping("/batch-refresh-metadata")
    public Result<String> batchRefreshMetadata() {
        komgaSyncService.batchRefreshAllKomgaMetadata();
        return Result.success("批量刷新任务已在后台启动！请查看控制台日志获取进度。");
    }

    @Autowired
    private com.checker.service.EhGalleriesService ehGalleriesService;

    /**
     * 启动文件体积回填任务，更新缺失或需要核对的大小信息。
     */
    @PostMapping("/batch-update-filesize")
    public Result<String> batchUpdateFileSize() {
        ehGalleriesService.batchUpdateFileSizes();
        return Result.success("批量更新文件大小任务已在后台启动！请查看控制台日志获取进度。");
    }
}
