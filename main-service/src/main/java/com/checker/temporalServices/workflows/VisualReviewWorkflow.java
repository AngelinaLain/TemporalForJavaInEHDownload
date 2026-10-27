package com.checker.temporalServices.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 定义按任务 ID 启动视觉复核的工作流入口。
 */
@WorkflowInterface
public interface VisualReviewWorkflow {

    /**
     * 启动指定任务的视觉复核处理。
     * */
    @WorkflowMethod
    void execute(String jobId);
}
