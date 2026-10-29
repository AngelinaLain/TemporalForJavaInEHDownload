package com.checker.event;

import org.springframework.context.ApplicationEvent;
import com.checker.service.mail.NotificationContext;

/**
 * 承载待发送邮件的主题和内容，让工作流活动只发布事件，实际发送交给后台监听器。
 */
public class NotificationEvent extends ApplicationEvent {

    private final String subject;
    private final String content;
    private final NotificationContext context;

    public NotificationEvent(Object source, String subject, String content) {
        this(source, subject, content, NotificationContext.now());
    }

    public NotificationEvent(Object source, String subject, String content, NotificationContext context) {
        super(source);
        this.subject = subject;
        this.content = content;
        this.context = context == null ? NotificationContext.now() : context;
    }

    public String getSubject() {
        return subject;
    }

    public String getContent() {
        return content;
    }

    public NotificationContext getContext() {
        return context;
    }
}
