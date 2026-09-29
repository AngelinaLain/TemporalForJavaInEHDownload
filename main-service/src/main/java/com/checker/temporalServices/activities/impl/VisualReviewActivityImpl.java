package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.service.VisualEmbeddingReviewService;
import com.checker.temporalServices.activities.VisualReviewActivity;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class VisualReviewActivityImpl implements VisualReviewActivity {
    private final VisualEmbeddingReviewService reviewService;

    public VisualReviewActivityImpl(VisualEmbeddingReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
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
