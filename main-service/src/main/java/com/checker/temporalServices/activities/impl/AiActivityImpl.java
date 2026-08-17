package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.service.impl.AiSummaryService;
import com.checker.temporalServices.activities.AiActivity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.AI_TASK_QUEUE) // AI 服务队列
public class AiActivityImpl implements AiActivity {

    @Autowired
    private AiSummaryService aiSummaryService;

    @Override
    public String generateGallerySummary(String title, List<String> tags) {
        // 内部已接入 Resilience4j 断路器：
        // - 瞬时 503 上抛 → Temporal 重试；
        // - 熔断打开 / 最终失败 → 自动降级为本地词典兜底（英文 Tag 透传）。
        return aiSummaryService.generateSummaryWithFallback(title, tags);
    }
}
