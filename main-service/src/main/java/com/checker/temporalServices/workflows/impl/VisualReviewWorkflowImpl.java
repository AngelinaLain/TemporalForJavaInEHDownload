package com.checker.temporalServices.workflows.impl;

import com.checker.common.Constants;
import com.checker.temporalServices.activities.VisualReviewActivity;
import com.checker.temporalServices.workflows.VisualReviewWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

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
    public void execute(String jobId) {
        activity.execute(jobId);
    }
}
