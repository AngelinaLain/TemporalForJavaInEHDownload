package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.event.NotificationEvent;
import com.checker.temporalServices.activities.NotificationActivity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 通知 Activity 实现：通过 Spring ApplicationEvent 解耦。
 * 本 Activity 只负责发布 {@link NotificationEvent} 事件（快速返回，不阻塞工作流），
 * 实际邮件发送由 NotificationEventListener 在后台线程池异步消费。
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class NotificationActivityImpl implements NotificationActivity {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void sendEmailAlert(String subject, String content) {
        log.info("📢 发布通知事件（异步发送邮件）: {}", subject);
        eventPublisher.publishEvent(new NotificationEvent(this, subject, content));
    }
}
