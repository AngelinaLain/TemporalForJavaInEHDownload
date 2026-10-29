package com.checker.service.impl;

import com.checker.config.EhNetworkConfig;
import com.checker.mapper.NotificationGalleryMapper;
import com.checker.service.mail.NotificationContext;
import com.checker.service.mail.NotificationEmailComposer;
import com.checker.service.mail.NotificationEmailRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailNotificationPayloadTest {
    @Test void graphPayloadUsesTheRenderedTemplateAndPlainTextSubjectWithoutSendingAnything() {
        EhNetworkConfig config = new EhNetworkConfig();
        config.getNotification().setAdminEmail("recipient@example.test");
        config.getNotification().setSenderEmail("sender@example.test");
        var email = new NotificationEmailComposer(config, mock(NotificationGalleryMapper.class))
                .compose("邮件测试 <检查> & 状态", "<script>alert('fixture')</script>\n普通正文", new NotificationContext(null, null, 1));
        String rendered = new NotificationEmailRenderer().render(email);
        var payload = EmailNotificationService.buildMailPayload(email, rendered, config.getNotification());
        var message = payload.getJSONObject("message");
        assertEquals("邮件测试 <检查> & 状态", message.getStr("subject"));
        assertEquals("HTML", message.getJSONObject("body").getStr("contentType"));
        assertEquals(rendered, message.getJSONObject("body").getStr("content"));
        assertFalse(rendered.contains("<script>"));
        assertTrue(rendered.contains("&lt;script&gt;"));
        assertEquals("recipient@example.test", message.getJSONArray("toRecipients").getJSONObject(0).getJSONObject("emailAddress").getStr("address"));
        assertEquals("sender@example.test", message.getJSONObject("from").getJSONObject("emailAddress").getStr("address"));
        assertEquals(false, payload.get("saveToSentItems"));
    }

    @Test void incompleteNotificationConfigurationSkipsBeforeAnyCompositionOrNetworkWork() {
        EhNetworkConfig config = new EhNetworkConfig();
        NotificationEmailComposer composer = mock(NotificationEmailComposer.class);
        NotificationEmailRenderer renderer = mock(NotificationEmailRenderer.class);
        EmailNotificationService sender = new EmailNotificationService(config, composer, renderer);
        assertFalse(sender.sendEmail("测试", "正文"));
        config.getNotification().setAdminEmail("recipient@example.test");
        config.getNotification().setTenantId("tenant-fixture");
        assertFalse(sender.sendEmail("测试", "正文", NotificationContext.now()));
        verifyNoInteractions(composer, renderer);
    }
}
