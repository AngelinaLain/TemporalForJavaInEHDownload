package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface VisualReviewActivity {
    @ActivityMethod
    void execute(String jobId);
}
