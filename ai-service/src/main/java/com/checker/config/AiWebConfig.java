package com.checker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AiWebConfig implements WebMvcConfigurer {
    private final InternalAiApiInterceptor interceptor;

    public AiWebConfig(InternalAiApiInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/ai/dynamic/**", "/api/ai/embedding/**");
    }
}
