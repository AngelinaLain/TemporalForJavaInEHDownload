package com.checker.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class NetworkConfig {

    @Bean
    @LoadBalanced // 核心注解：让 RestTemplate 拦截请求并去 Nacos 查找 IP
    public RestTemplate loadBalancedRestTemplate(@Value("${ai.internal-token:}") String aiInternalToken) {
        RestTemplate template = new RestTemplate();
        if (aiInternalToken != null && !aiInternalToken.isBlank()) {
            template.getInterceptors().add((request, body, execution) -> {
                request.getHeaders().set("X-AI-Internal-Token", aiInternalToken);
                return execution.execute(request, body);
            });
        }
        return template;
    }
}
