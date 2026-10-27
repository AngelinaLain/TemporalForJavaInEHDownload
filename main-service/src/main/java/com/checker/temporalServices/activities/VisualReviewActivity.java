package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 定义按任务 ID 执行一次视觉复核的后台操作。
 */
@ActivityInterface
public interface VisualReviewActivity {
    @ActivityMethod
    /** 根据已有任务 ID 执行视觉复核，进度与结果写回对应任务记录。 */
    void execute(String jobId);
}
