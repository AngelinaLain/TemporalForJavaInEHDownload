package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 集中读取源站、代理、群晖、Komga 和下载配置，供不同服务使用同一份连接设置。
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
    private TagDb tagDb = new TagDb();
    private Smb smb = new Smb();
    private ArchiveStorage archiveStorage = new ArchiveStorage();
    private Download download = new Download();

    /** 多代理轮换池：配置后优先于单一 proxy 使用，遇到 403/502 自动冷却并切换 */
    private List<Proxy> proxyPool = new ArrayList<>();

    /** 多账号 Cookie 轮转池：配置后按请求轮询分散单账号频率，Cookie 失效自动切换 */
    private List<Cookies> cookiePool = new ArrayList<>();

    /** 保存一个源站账号的 Cookie 字段，供请求认证与账号轮换使用。 */
    @Data
    public static class Cookies {
        private String memberId;
        private String passHash;
        private String sk;
        private String star;

        /**
         * 按源站要求拼接当前账号的 Cookie 请求头。
         */
        public String getFullCookieString() {
            return String.format("ipb_member_id=%s; ipb_pass_hash=%s; sk=%s; star=%s;",
                    memberId, passHash, sk, star);
        }

        /**
         * 检查该账号或代理是否具备参与请求所需的基本配置。
         */
        public boolean isConfigured() {
            return memberId != null && !memberId.isBlank()
                    && passHash != null && !passHash.isBlank()
                    && sk != null && !sk.isBlank()
                    && star != null && !star.isBlank();
        }
    }

    /** 保存单个代理的类型、地址和认证信息，供网络客户端建立连接。 */
    @Data
    public static class Proxy {
        private String host;
        private Integer port;
        private String username;
        private String password;
        private String type;

        /**
         * 把代理类型、地址和认证信息拼成代理连接地址，供网络客户端使用。
         */
        public String getProxyUrl() {
            return String.format("%s://%s:%s@%s:%d", type, username, password, host, port);
        }

        /**
         * 检查该账号或代理是否具备参与请求所需的基本配置。
         */
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
        /** 浏览器跳转使用的地址；留空时使用服务端地址，不在地址中附带 API 密钥。 */
        private String publicUrl;
        private String libraryId;
        private String apiKey;
    }

    /**
     * EhTagTranslation 翻译数据库下载配置
     */
    @Data
    public static class TagDb {
        /**
         * 自定义翻译数据库镜像地址（可选）。
         * 配置后优先于内置默认镜像使用，可指向自建/内网镜像以规避网络限制。
         */
        private List<String> urls = new ArrayList<>();
    }

    /**
     * 群晖 SMB/CIFS 共享配置：本地下载后上传到 Komga 库目录的首选方式。
     */
    @Data
    public static class Smb {
        private String host;
        /** 共享名，如 "video" 或 "komga" */
        private String share;
        /** 共享内的相对目录，如 "/docker/komga/library" */
        private String path;
        private String username;
        private String password;
        private String domain;
    }

    /**
     * 配置当前进程可直接访问的 Komga 图库根目录。
     * 挂载可用时优先直接读写文件，否则原有归档流程可继续使用 SMB 或 SFTP。
     */
    @Data
    public static class ArchiveStorage {
        /** 当前进程或容器内部的图库绝对路径，例如 /data/komga-library。 */
        private String mountPath;
    }

    /**
     * 下载模式配置
     */
    @Data
    public static class Download {
        /**
         * 下载模式：
         * <ul>
         *   <li>{@code local}：本地下载 + 注入 ComicInfo.xml + 上传群晖（默认，更可靠）</li>
         *   <li>{@code downloadstation}：沿用群晖 DownloadStation 下载（兜底）</li>
         * </ul>
         */
        private String mode = "local";
        /** 本地下载临时目录（默认系统临时目录） */
        private String tempDir;
        /** 大文件下载建立 TCP/TLS 连接的超时秒数。 */
        private int connectTimeoutSeconds = 60;
        /** 下载流连续无数据时的读取超时秒数，不限制完整文件总耗时。 */
        private int readTimeoutSeconds = 120;
        /** 请求写入超时秒数。 */
        private int writeTimeoutSeconds = 60;
        /** 单次 Activity 内允许的断点续传/代理切换次数。 */
        private int maxAttempts = 8;
        /** 网络失败后的首次退避秒数。 */
        private int initialBackoffSeconds = 5;
        /** 指数退避的最大秒数。 */
        private int maxBackoffSeconds = 120;
    }
}
