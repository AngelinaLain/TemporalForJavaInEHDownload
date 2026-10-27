package com.checker.temporalServices.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 定义失败记录的批量恢复流程，区分重新下载与仅补偿入库。
 */
@WorkflowInterface
public interface RetryFailedDownloadWorkflow {

    /**
     * 重试所有失败/已下载未入库的画廊任务
     */
    @WorkflowMethod
    void retryFailedTasks();
}
