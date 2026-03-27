package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "eh-config")
public class EhNetworkConfig {
    private Cookies cookies = new Cookies();
    private Proxy proxy = new Proxy();
    private Synology synology = new Synology();
    private Notification notification = new Notification();
    private Komga komga = new Komga();

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
    }
    @Data
    public static class Synology {
        private String url;
        private String username;
        private String password;
        private String destination;
        private String type;
    }
    @Data
    public static class Notification {
        private String adminEmail;
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private String senderEmail;
    }
    @Data
    public static class Komga {
        private String url;
        private String username;
        private String password;
        private String libraryId;
    }
}
