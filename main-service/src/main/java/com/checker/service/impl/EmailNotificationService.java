package com.checker.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.config.EhNetworkConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private EhNetworkConfig netConfig;

    /** Graph API Token 缓存，有效期 50 分钟（Token 实际有效 1 小时，提前 10 分钟刷新） */
    private final Cache<String, String> tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(50, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 发送邮件；未配置通知参数时静默跳过（返回 false）。
     *
     * @return true 表示已发送或成功跳过；抛异常表示发送失败
     */
    public boolean sendEmail(String subject, String content) {
        EhNetworkConfig.Notification notifConfig = netConfig.getNotification();
        if (StrUtil.isBlank(notifConfig.getAdminEmail()) || StrUtil.isBlank(notifConfig.getTenantId())) {
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
        JSONObject mailPayload = buildMailPayload(subject, content, notifConfig);

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
     * 构建 Graph API sendMail 请求体（含精美 HTML 模板）
     */
    private static JSONObject buildMailPayload(String subject, String content,
                                               EhNetworkConfig.Notification notifConfig) {
        String safeSubject = subject.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String htmlSafeContent = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        String currentTime = DateUtil.now();
        String htmlBody = String.format(
                "<!DOCTYPE html><html><head><style>" +
                "body{font-family:'Segoe UI',Tahoma,Geneva,Verdana,sans-serif;background-color:#f9f9f9;color:#333;margin:0;padding:20px}" +
                ".card{background:#fff;border:1px solid #e0e0e0;border-radius:8px;padding:24px;max-width:600px;margin:0 auto;box-shadow:0 2px 4px rgba(0,0,0,.05)}" +
                ".header{border-bottom:2px solid #0078D4;padding-bottom:12px;margin-bottom:20px;font-size:20px;font-weight:bold;color:#0078D4}" +
                ".content{font-size:15px;line-height:1.6}" +
                ".footer{margin-top:30px;padding-top:15px;border-top:1px dashed #ccc;font-size:12px;color:#888}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<div class='header'>%s</div>" +
                "<div class='content'>%s</div>" +
                "<div class='footer'><strong>触发时间：</strong>%s<br><strong>系统来源：</strong>EHentai 自动化工作流 (Temporal)</div>" +
                "</div></body></html>",
                safeSubject, htmlSafeContent, currentTime
        );

        JSONObject message = new JSONObject();
        message.set("subject", subject);

        JSONObject fromAddr = new JSONObject();
        fromAddr.set("address", notifConfig.getSenderEmail());
        fromAddr.set("name", "EHentai 自动化机器人");
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
        mailPayload.set("saveToSentItems", "false");
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

        String tokenResp = HttpRequest.post(tokenUrl).form(tokenForm).timeout(10000).execute().body();
        JSONObject tokenJson = JSONUtil.parseObj(tokenResp);
        String accessToken = tokenJson.getStr("access_token");
        if (StrUtil.isBlank(accessToken)) {
            log.error("获取 Graph Token 失败: {}", tokenResp);
            return null;
        }
        log.info("✅ Graph API Token 已获取并缓存");
        return accessToken;
    }
}
