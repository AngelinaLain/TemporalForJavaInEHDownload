package com.checker.temporalServices.activities.impl;

import com.checker.common.Constants;
import com.checker.event.NotificationEvent;
import com.checker.service.mail.NotificationContext;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import com.checker.temporalServices.activities.NotificationActivity;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 将工作流通知发布为应用事件；后台监听器负责发邮件，工作流无需等待实际发送。
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class NotificationActivityImpl implements NotificationActivity {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    /** 发布一条带主题和正文的通知事件，由后台监听器完成邮件发送。 */
    public void sendEmailAlert(String subject, String content) {
        log.info("📢 发布通知事件（异步发送邮件）: {}", subject);
        NotificationContext context = NotificationContext.now();
        try {
            // 测试邮件由控制器直接调用，不存在 Activity 上下文；真实任务则保留精确的工作流身份。
            ActivityInfo info = Activity.getExecutionContext().getInfo();
            context = new NotificationContext(info.getWorkflowId(), info.getRunId(), context.triggeredAt());
        } catch (IllegalStateException ignored) {
            // 普通接口触发的邮件只展示触发时间，不编造工作流编号。
        }
        eventPublisher.publishEvent(new NotificationEvent(this, subject, content, context));
    }
}
