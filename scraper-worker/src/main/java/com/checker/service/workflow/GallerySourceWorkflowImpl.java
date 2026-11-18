package com.checker.service.workflow;

import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceResult;
import com.checker.shared.Constants;
import com.checker.shared.ErrorType;
import com.checker.temporalServices.activities.GallerySourceActivity;
import com.checker.temporalServices.workflows.GallerySourceWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.io.IOException;
import java.time.Duration;

/** 短工作流只编排源站读取，不访问主服务数据库或归档。 */
@WorkflowImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class GallerySourceWorkflowImpl implements GallerySourceWorkflow {
    static final ActivityOptions SOURCE_OPTIONS = ActivityOptions.newBuilder()
            .setTaskQueue(Constants.SCRAPER_TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofSeconds(45))
            .setScheduleToCloseTimeout(Duration.ofSeconds(100))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setMaximumAttempts(2)
                    .setDoNotRetry(IllegalArgumentException.class.getName(),
                            ErrorType.QUOTA_EXCEEDED.getCode(), ErrorType.COOKIE_EXPIRED.getCode(),
                            ErrorType.IP_BANNED.getCode())
                    .build())
            .build();

    private final GallerySourceActivity source = Workflow.newActivityStub(GallerySourceActivity.class, SOURCE_OPTIONS);

    @Override
    public GallerySourceResult collect(GallerySourceRequest request) {
        try {
            return source.collectGallerySource(request);
        } catch (IOException failure) {
            throw Workflow.wrap(failure);
        }
    }
}
