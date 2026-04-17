package com.checker.temporalServices.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 重试失败下载工作流接口：对数据库中所有“下载失败”状态的画廊重新执行下载流程
 */
@WorkflowInterface
public interface RetryFailedDownloadWorkflow {

    /**
     * 重试所有失败/已下载未入库的画廊任务
     */
    @WorkflowMethod
    void retryFailedTasks();
}
