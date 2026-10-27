package com.checker.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnJava;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.system.JavaVersion;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 为支持的 Java 版本启用虚拟线程；环境不支持时使用有容量限制的普通线程池。
 */
@Slf4j
@Configuration
public class VirtualThreadConfig {

    static final boolean VIRTUAL_THREADS_SUPPORTED = Runtime.version().feature() >= 21;

    /**
     * 优先创建适合网络等待任务的虚拟线程执行器，运行环境不支持或创建失败时回退到普通线程池。
     */
    @Bean("virtualThreadExecutor")
    @ConditionalOnMissingBean(name = "virtualThreadExecutor")
    public AsyncTaskExecutor virtualThreadExecutor() {
        if (VIRTUAL_THREADS_SUPPORTED) {
            try {
                Method factory = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
                ExecutorService executorService = (ExecutorService) factory.invoke(null);
                log.info("✅ 已启用 Java 21 虚拟线程执行器（网络 I/O 密集型任务）");
                return new TaskExecutorAdapter(executorService);
            } catch (Exception e) {
                log.warn("虚拟线程执行器初始化失败，回退为平台线程池: {}", e.getMessage());
            }
        } else {
            log.warn("当前运行于 Java {}，虚拟线程需要 Java 21+，回退为平台线程池", Runtime.version().feature());
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("vt-fallback-");
        executor.initialize();
        return executor;
    }

    /**
     * 在支持的运行环境中把 Tomcat 请求执行器切换为虚拟线程，切换失败时保留原执行方式。
     */
    @Bean
    @ConditionalOnJava(JavaVersion.TWENTY_ONE)
    @ConditionalOnMissingBean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
        return protocolHandler -> {
            try {
                Method factory = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
                Executor executor = (Executor) factory.invoke(null);
                Method setter = protocolHandler.getClass().getMethod("setExecutor", Executor.class);
                setter.invoke(protocolHandler, executor);
                log.info("✅ Tomcat 已切换到虚拟线程协议处理器");
            } catch (Exception e) {
                log.warn("Tomcat 虚拟线程切换失败: {}", e.getMessage());
            }
        };
    }
}
