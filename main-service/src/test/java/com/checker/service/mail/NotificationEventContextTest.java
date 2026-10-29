package com.checker.service.mail;

import com.checker.event.NotificationEvent;
import com.checker.event.NotificationEventListener;
import com.checker.service.impl.EmailNotificationService;
import com.checker.temporalServices.activities.impl.NotificationActivityImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationEventContextTest {
    @Test void controllerStyleInvocationWithoutTemporalContextStillPublishesATimedEvent() {
        NotificationActivityImpl activity = new NotificationActivityImpl();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(activity, "eventPublisher", publisher);
        long before = System.currentTimeMillis();
        activity.sendEmailAlert("邮件测试", "测试正文");
        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals("邮件测试", event.getValue().getSubject());
        assertEquals("测试正文", event.getValue().getContent());
        assertTrue(event.getValue().getContext().triggeredAt() >= before);
        assertNull(event.getValue().getContext().workflowId());
        assertNull(event.getValue().getContext().runId());
    }

    @Test @SuppressWarnings("unchecked")
    void asyncListenerPassesTheOriginalWorkflowIdentityAndTimestampToTheSender() {
        EmailNotificationService sender = mock(EmailNotificationService.class);
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        NotificationContext context = new NotificationContext("single-gallery-42", "run-fixture", 123456789L);
        NotificationEvent event = new NotificationEvent(this, "下载异常", "GID: 42", context);
        new NotificationEventListener(sender, redis).onNotification(event);
        verify(sender).sendEmail("下载异常", "GID: 42", context);
        verifyNoMoreInteractions(sender);
    }
}
