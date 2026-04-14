package com.checker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Admin admin = new Admin();

    @Data
    public static class Jwt {
        private String secret;
        private long expiration = 86400000;
    }

    @Data
    public static class Admin {
        private String username = "admin";
        private String password = "admin123";
    }
}
