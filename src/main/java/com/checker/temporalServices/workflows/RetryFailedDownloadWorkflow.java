package com.checker.temporalServices.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RetryFailedDownloadWorkflow {
    @WorkflowMethod
    void retryFailedTasks();
}
