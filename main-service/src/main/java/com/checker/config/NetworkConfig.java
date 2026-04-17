package com.checker.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class NetworkConfig {

    @Bean
    @LoadBalanced // 核心注解：让 RestTemplate 拦截请求并去 Nacos 查找 IP
    public RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate();
    }
}
