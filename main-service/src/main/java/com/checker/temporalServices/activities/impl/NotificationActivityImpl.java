package com.checker.temporalServices.activities.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.common.Constants;
import com.checker.common.ErrorType;
import com.checker.config.EhNetworkConfig;
import com.checker.temporalServices.activities.NotificationActivity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通知 Activity 实现：通过 Microsoft Graph API 发送邮件通知
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class NotificationActivityImpl implements NotificationActivity {

    @Autowired
    private EhNetworkConfig netConfig;

    /** Graph API Token 缓存，有效期 50 分钟（Token 实际有效 1 小时，提前 10 分钟刷新） */
    private final Cache<String, String> tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(50, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    @Override
    public void sendEmailAlert(String subject, String content) {
        EhNetworkConfig.Notification notifConfig = netConfig.getNotification();
        if (StrUtil.isBlank(notifConfig.getAdminEmail()) || StrUtil.isBlank(notifConfig.getTenantId())) {
            log.warn("未配置完整的邮件通知参数，跳过。主题: {}", subject);
            return;
        }
        try {
            // 1. 从缓存获取 Token（过期自动刷新）
            String accessToken = tokenCache.get("graphToken", key -> fetchGraphToken(notifConfig));
            if (StrUtil.isBlank(accessToken)) {
                log.error("获取 Graph Token 失败");
                return;
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
                } else {
                    log.error("❌ 发送邮件失败, HTTP: {}, body: {}", mailResp.getStatus(), mailResp.body());
                }
            }
        } catch (Exception e) {
            log.error("❌ 邮件通知异常: {}", e.getMessage());
            throw ApplicationFailure.newFailure("邮件发送失败: " + e.getMessage(), ErrorType.EMAIL_SEND_FAILED.getCode());
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
