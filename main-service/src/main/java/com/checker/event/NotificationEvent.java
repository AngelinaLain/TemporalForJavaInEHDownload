package com.checker.event;

import org.springframework.context.ApplicationEvent;

/**
 * 通知事件：从 Temporal 编排中解耦的异步邮件通知。
 * 通知 Activity 只负责发布事件（快速返回），实际邮件发送由
 * {@code NotificationEventListener} 在独立线程池异步消费。
 */
public class NotificationEvent extends ApplicationEvent {

    private final String subject;
    private final String content;

    public NotificationEvent(Object source, String subject, String content) {
        super(source);
        this.subject = subject;
        this.content = content;
    }

    public String getSubject() {
        return subject;
    }

    public String getContent() {
        return content;
    }
}
