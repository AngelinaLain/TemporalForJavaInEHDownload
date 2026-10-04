package com.checker.temporalServices.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface VisualReviewWorkflow {
    @WorkflowMethod
    void execute(String jobId);
}
