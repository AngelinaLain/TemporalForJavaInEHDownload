package com.checker.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一配置本地缓存和后台线程池，让维护任务与图像计算分别使用有容量限制的执行资源。
 */
@Configuration
@EnableCaching
public class CacheConfig {
    /**
     * 创建通用本地缓存，限制保存条数并设置过期时间，避免缓存随请求不断增长。
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .initialCapacity(100)
                .maximumSize(10_000));
        return cacheManager;
    }

    /**
     * 创建用户触发的维护任务线程池，把耗时网络与存储操作放到后台执行。
     * 线程和等待队列都有上限，队列满后拒绝新任务，避免同时启动过多维护工作。
     */
    @Bean(name = "backgroundTaskExecutor")
    public TaskExecutor backgroundTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("gallery-maintenance-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 创建抽样页解码和感知哈希计算专用线程池，避免大批图片计算挤占邮件等维护任务。
     * 未指定并发数时按处理器数量选取保守值；队列满后由提交线程执行，让提交速度随处理能力降低。
     */
    @Bean(name = "visualFingerprintExecutor")
    public TaskExecutor visualFingerprintExecutor(
            @Value("${eh-config.visual-fingerprint.parallelism:0}") int configuredParallelism) {
        int detectedCores = Runtime.getRuntime().availableProcessors();
        int parallelism = configuredParallelism > 0
                ? configuredParallelism
                : Math.max(1, Math.min(detectedCores, 8));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setQueueCapacity(Math.max(32, parallelism * 4));
        executor.setThreadNamePrefix("visual-fingerprint-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
