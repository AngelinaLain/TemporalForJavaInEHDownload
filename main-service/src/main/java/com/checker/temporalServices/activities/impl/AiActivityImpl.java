package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.temporalServices.activities.AiActivity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.AI_TASK_QUEUE) // 主服务队列
public class AiActivityImpl implements AiActivity {

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    @Override
    public String generateGallerySummary(String title, List<String> tags) {
        String url = "http://eh-ai-service/api/ai/generate-summary";
        Map<String, Object> req = new HashMap<>();
        req.put("title", title);
        req.put("tags", tags);

        try {
            return loadBalancedRestTemplate.postForObject(url, req, String.class);
        } catch (HttpServerErrorException.ServiceUnavailable e) {
            log.warn("💤 GPU节点离线，触发挂起重试");
            throw e;
        }
    }
}
