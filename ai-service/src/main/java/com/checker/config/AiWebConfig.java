package com.checker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 为动态模型和视觉向量接口安装内部调用凭据检查。 */
@Configuration
public class AiWebConfig implements WebMvcConfigurer {
    private final InternalAiApiInterceptor interceptor;

    public AiWebConfig(InternalAiApiInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /** 只保护服务之间调用的两个接口前缀，其余接口沿用原有访问规则。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/ai/dynamic/**", "/api/ai/embedding/**");
    }
}
