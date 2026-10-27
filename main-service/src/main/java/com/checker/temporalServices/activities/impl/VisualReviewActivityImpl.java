package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.service.VisualEmbeddingReviewService;
import com.checker.temporalServices.activities.VisualReviewActivity;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/**
 * 将任务队列中的视觉复核任务交给复核服务，并汇报开始与结束心跳。
 */
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class VisualReviewActivityImpl implements VisualReviewActivity {
    private final VisualEmbeddingReviewService reviewService;

    public VisualReviewActivityImpl(VisualEmbeddingReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    /** 在实际复核前后发送心跳，保留运行异常供工作流的重试机制处理。 */
    public void execute(String jobId) {
        try {
            Activity.getExecutionContext().heartbeat("开始视觉向量复核");
            reviewService.execute(jobId);
            Activity.getExecutionContext().heartbeat("视觉向量复核完成");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }
}
