package com.checker.event;

import com.checker.service.impl.EmailNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 在后台消费邮件通知事件并发送邮件，同时在 Redis 可用时发布通知副本。
 */
@Slf4j
@Component
public class NotificationEventListener {

    private static final String REDIS_CHANNEL = "app:notifications";

    private final EmailNotificationService emailNotificationService;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public NotificationEventListener(EmailNotificationService emailNotificationService,
                                     ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.emailNotificationService = emailNotificationService;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    /**
     * 异步发送事件中的邮件，发送失败记录错误，再尝试向 Redis 发布通知，不阻塞原工作流。
     */
    @Async("backgroundTaskExecutor")
    @EventListener
    public void onNotification(NotificationEvent event) {
        log.info("📨 收到通知事件，异步发送邮件: {}", event.getSubject());
        try {
            emailNotificationService.sendEmail(event.getSubject(), event.getContent());
        } catch (Exception e) {
            log.error("❌ 异步邮件发送失败: {}", e.getMessage());
        }
        publishToRedisIfAvailable(event);
    }

    /**
     * 仅在 Redis 可用时发布通知副本，发布失败不影响已执行的邮件发送。
     */
    private void publishToRedisIfAvailable(NotificationEvent event) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.convertAndSend(REDIS_CHANNEL,
                    event.getSubject() + "\n" + event.getContent());
        } catch (Exception e) {
            log.debug("Redis Pub/Sub 发布失败（忽略）: {}", e.getMessage());
        }
    }
}
