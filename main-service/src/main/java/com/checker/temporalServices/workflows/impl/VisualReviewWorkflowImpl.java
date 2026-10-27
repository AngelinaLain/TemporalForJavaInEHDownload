package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.temporalServices.activities.VisualReviewActivity;
import com.checker.temporalServices.workflows.VisualReviewWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * 为单次视觉复核配置执行超时与重试，再交给后台活动处理。
 */
@WorkflowImpl(taskQueues = Constants.TASK_QUEUE)
public class VisualReviewWorkflowImpl implements VisualReviewWorkflow {
    private final VisualReviewActivity activity = Workflow.newActivityStub(VisualReviewActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(Constants.TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(45))
                    .setHeartbeatTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setMaximumInterval(Duration.ofMinutes(1))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    /** 按任务 ID 调用视觉复核活动，超时与重试由本工作流的活动配置控制。 */
    public void execute(String jobId) {
        activity.execute(jobId);
    }
}
