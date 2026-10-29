package com.checker.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.config.EhNetworkConfig;
import com.checker.service.mail.NotificationContext;
import com.checker.service.mail.NotificationEmail;
import com.checker.service.mail.NotificationEmailComposer;
import com.checker.service.mail.NotificationEmailRenderer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 邮件通知服务：负责 Microsoft Graph API 的 Token 获取与邮件发送。
 * 从 NotificationActivity 中抽离，供事件监听器异步调用，
 * 实现通知与 Temporal 编排的解耦。
 */
@Slf4j
@Service
public class EmailNotificationService {

    private final EhNetworkConfig netConfig;
    private final NotificationEmailComposer composer;
    private final NotificationEmailRenderer renderer;

    public EmailNotificationService(EhNetworkConfig netConfig, NotificationEmailComposer composer,
                                    NotificationEmailRenderer renderer) {
        this.netConfig = netConfig;
        this.composer = composer;
        this.renderer = renderer;
    }

    /** Graph API Token 缓存，有效期 50 分钟（Token 实际有效 1 小时，提前 10 分钟刷新） */
    private final Cache<String, String> tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(50, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 发送邮件；未配置通知参数时静默跳过（返回 false）。
     *
     * @return true 表示邮件已发送；配置或令牌不完整时返回 false，请求发送失败则抛异常
     */
    public boolean sendEmail(String subject, String content) {
        return sendEmail(subject, content, NotificationContext.now());
    }

    /** 在异步线程整理和渲染通知，工作流只发布事件，不等待画廊概况查询或 Graph 请求。 */
    public boolean sendEmail(String subject, String content, NotificationContext context) {
        EhNetworkConfig.Notification notifConfig = netConfig.getNotification();
        if (StrUtil.hasBlank(notifConfig.getAdminEmail(), notifConfig.getTenantId(), notifConfig.getClientId(),
                notifConfig.getClientSecret(), notifConfig.getSenderEmail())) {
            log.warn("未配置完整的邮件通知参数，跳过。主题: {}", subject);
            return false;
        }

        // 1. 从缓存获取 Token（过期自动刷新）
        String accessToken = tokenCache.get("graphToken", key -> fetchGraphToken(notifConfig));
        if (StrUtil.isBlank(accessToken)) {
            log.error("获取 Graph Token 失败");
            return false;
        }

        // 2. 组装邮件 JSON
        NotificationEmail email = composer.compose(subject, content, context);
        JSONObject mailPayload = buildMailPayload(email, renderer.render(email), notifConfig);

        // 3. 发送邮件
        String sendMailUrl = String.format("https://graph.microsoft.com/v1.0/users/%s/sendMail",
                notifConfig.getSenderEmail());
        try (HttpResponse mailResp = HttpRequest.post(sendMailUrl)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .body(mailPayload.toString())
                .timeout(15000)
                .execute()) {
            if (mailResp.isOk() || mailResp.getStatus() == 202) {
                log.info("✅ 邮件已发送至: {}", notifConfig.getAdminEmail());
                return true;
            }
            log.error("❌ 发送邮件失败, HTTP: {}, body: {}", mailResp.getStatus(), mailResp.body());
            throw new IllegalStateException("邮件发送失败: HTTP " + mailResp.getStatus());
        }
    }

    /**
     * 用已经安全渲染的 HTML 构建 Graph 请求体，JSON 主题保留普通文本，不重复做 HTML 转义。
     */
    static JSONObject buildMailPayload(NotificationEmail email, String htmlBody,
                                        EhNetworkConfig.Notification notifConfig) {
        JSONObject message = new JSONObject();
        message.set("subject", email.subject());

        JSONObject fromAddr = new JSONObject();
        fromAddr.set("address", notifConfig.getSenderEmail());
        fromAddr.set("name", "GalleryImport 通知中心");
        JSONObject from = new JSONObject();
        from.set("emailAddress", fromAddr);
        message.set("from", from);

        JSONObject body = new JSONObject();
        body.set("contentType", "HTML");
        body.set("content", htmlBody);
        message.set("body", body);

        JSONObject recipientAddr = new JSONObject();
        recipientAddr.set("address", notifConfig.getAdminEmail());
        JSONObject recipient = new JSONObject();
        recipient.set("emailAddress", recipientAddr);
        JSONArray toRecipients = new JSONArray();
        toRecipients.add(recipient);
        message.set("toRecipients", toRecipients);

        JSONObject mailPayload = new JSONObject();
        mailPayload.set("message", message);
        mailPayload.set("saveToSentItems", false);
        return mailPayload;
    }

    /**
     * 向 Microsoft Identity Platform 请求 OAuth2 Client Credentials Token
     */
    private String fetchGraphToken(EhNetworkConfig.Notification notifConfig) {
        String tokenUrl = String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token",
                notifConfig.getTenantId());
        Map<String, Object> tokenForm = new HashMap<>();
        tokenForm.put("client_id", notifConfig.getClientId());
        tokenForm.put("client_secret", notifConfig.getClientSecret());
        tokenForm.put("scope", "https://graph.microsoft.com/.default");
        tokenForm.put("grant_type", "client_credentials");

        try (HttpResponse response = HttpRequest.post(tokenUrl).form(tokenForm).timeout(10000).execute()) {
            JSONObject tokenJson = JSONUtil.parseObj(response.body());
            String accessToken = tokenJson.getStr("access_token");
            if (StrUtil.isBlank(accessToken)) {
                log.error("获取 Graph Token 失败, HTTP: {}", response.getStatus());
                return null;
            }
            log.info("✅ Graph API Token 已获取并缓存");
            return accessToken;
        }
    }
}
