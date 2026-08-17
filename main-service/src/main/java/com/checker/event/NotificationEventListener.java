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
 * 通知事件监听器：异步消费 {@link NotificationEvent}，在后台线程池执行邮件发送，
 * 使 Temporal 通知 Activity 只负责发布事件、立即返回，降低编排耦合。
 * 若配置了 Redis，同时向 Pub/Sub 频道发布一份，供其他实例/未来消费者扩展。
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
