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
 * 虚拟线程支持配置（Java 21+）：
 * <ul>
 *   <li>运行于 Java 21+ 时启用虚拟线程执行器与 Tomcat 虚拟线程协议处理器；</li>
 *   <li>运行于 Java 17 时自动回退为平台线程池，保证旧环境兼容。</li>
 * </ul>
 * 全部通过反射调用 {@code Executors.newVirtualThreadPerTaskExecutor}，保证项目在
 * Java 17 下也能正常编译。
 */
@Slf4j
@Configuration
public class VirtualThreadConfig {

    static final boolean VIRTUAL_THREADS_SUPPORTED = Runtime.version().feature() >= 21;

    /**
     * 网络 I/O 密集型任务使用的虚拟线程执行器（Java 21+ 生效）。
     * 组件通过 @Qualifier("virtualThreadExecutor") 注入即可。
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
     * Tomcat 协议处理器虚拟线程定制器，仅 Java 21+ 生效（通过反射设置 Executor）。
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
