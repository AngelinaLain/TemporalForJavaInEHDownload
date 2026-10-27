package com.checker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 启动 AI 服务，提供模型调用、本地视觉向量接口，并接收后台 AI 推理任务。
 */
@EnableDiscoveryClient
@SpringBootApplication
public class AiServiceApplication {
    /** 启动应用并加载模型服务、任务队列等配置。 */
    public static void main( String[] args ) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
