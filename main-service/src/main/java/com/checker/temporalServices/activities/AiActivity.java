package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

@ActivityInterface
public interface AiActivity {
    @ActivityMethod
    String generateGallerySummary(String title, List<String> tags);
}