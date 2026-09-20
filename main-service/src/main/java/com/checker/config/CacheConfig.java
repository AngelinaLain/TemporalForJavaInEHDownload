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

@Configuration
@EnableCaching
public class CacheConfig {
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
     * Bounded executor for user-triggered maintenance jobs.
     * Keeps long-running I/O away from the common ForkJoinPool and rejects work
     * once the queue is full instead of creating unbounded concurrent work.
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
     * CPU-bound pool used to decode sampled pages and calculate perceptual hashes in parallel.
     * It is intentionally separate from maintenance jobs so a large refresh cannot starve
     * notifications or other background work. Zero selects a conservative CPU-based default.
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
