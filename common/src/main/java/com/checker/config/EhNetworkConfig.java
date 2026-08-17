package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * EHentai 网络配置类，绑定 application.yaml 中 eh-config 前缀的属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "eh-config")
public class EhNetworkConfig {
    private Cookies cookies = new Cookies();
    private Proxy proxy = new Proxy();
    private Synology synology = new Synology();
    private Notification notification = new Notification();
    private Komga komga = new Komga();
    private RateLimit rateLimit = new RateLimit();

    /** 多代理轮换池：配置后优先于单一 proxy 使用，遇到 403/502 自动冷却并切换 */
    private List<Proxy> proxyPool = new ArrayList<>();

    /** 多账号 Cookie 轮转池：配置后按请求轮询分散单账号频率，Cookie 失效自动切换 */
    private List<Cookies> cookiePool = new ArrayList<>();

    @Data
    public static class Cookies {
        private String memberId;
        private String passHash;
        private String sk;
        private String star;

        /**
         * 方便直接获取拼装好的 Cookie 字符串用于 OkHttp 请求
         */
        public String getFullCookieString() {
            return String.format("ipb_member_id=%s; ipb_pass_hash=%s; sk=%s; star=%s;",
                    memberId, passHash, sk, star);
        }

        /** 账号是否完整配置（四个字段全部非空才可参与轮转） */
        public boolean isConfigured() {
            return memberId != null && !memberId.isBlank()
                    && passHash != null && !passHash.isBlank()
                    && sk != null && !sk.isBlank()
                    && star != null && !star.isBlank();
        }
    }

    @Data
    public static class Proxy {
        private String host;
        private Integer port;
        private String username;
        private String password;
        private String type;

        /**
         * 方便获取代理完整 URL
         */
        public String getProxyUrl() {
            return String.format("%s://%s:%s@%s:%d", type, username, password, host, port);
        }

        public boolean isConfigured() {
            return host != null && !host.isBlank() && port != null;
        }
    }

    /**
     * EHentai API 全局限流配置（令牌桶）
     */
    @Data
    public static class RateLimit {
        /** 是否启用全局限流 */
        private boolean enabled = true;
        /** 每个刷新周期内允许的请求数（令牌数） */
        private int limitForPeriod = 30;
        /** 令牌刷新周期（秒） */
        private long limitRefreshPeriodSeconds = 60;
        /** 获取令牌的最长等待时间（秒） */
        private long timeoutDurationSeconds = 30;
        /** 代理被 403/502 触发后的冷却时间（秒） */
        private long proxyCooldownSeconds = 300;
    }

    /**
     * 群晖 DownloadStation / FileStation 连接配置
     */
    @Data
    public static class Synology {
        private String url;
        private String username;
        private String password;
        private String destination;
        private String type;
    }

    /**
     * 邮件通知配置（基于 Microsoft Graph API）
     */
    @Data
    public static class Notification {
        private String adminEmail;
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private String senderEmail;
    }

    /**
     * Komga 漫画库管理服务连接配置
     */
    @Data
    public static class Komga {
        private String url;
        private String libraryId;
        private String apiKey;
    }
}
