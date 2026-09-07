package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/** AI 域 Activity：由 eh-ai-service 独占执行，主服务仅负责编排。 */
@ActivityInterface
public interface AiActivity {
    @ActivityMethod
    String generateGallerySummary(String title, List<String> tags);
}
