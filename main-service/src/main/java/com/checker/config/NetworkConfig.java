package com.checker.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 创建调用其他内部服务的网络客户端，通过服务名发现地址，并为 AI 内部请求附带访问令牌。
 */
@Configuration
public class NetworkConfig {

    /**
     * 创建支持服务发现的请求客户端；配置了 AI 内部令牌时，统一加入请求头供下游服务验证。
     */
    @Bean
    @LoadBalanced // 允许使用注册中心里的服务名发起请求，由服务发现机制选择实际地址。
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
