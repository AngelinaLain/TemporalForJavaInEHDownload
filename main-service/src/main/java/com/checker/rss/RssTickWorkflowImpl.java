package com.checker.rss;
import com.checker.common.Constants;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
@WorkflowImpl(taskQueues=Constants.TASK_QUEUE)
public class RssTickWorkflowImpl implements RssTickWorkflow {
    public void tick() {
        Workflow.newActivityStub(RssActivities.class,ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build()).build()).rssTick();
    }
}
