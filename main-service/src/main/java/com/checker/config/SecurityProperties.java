package com.checker.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取并校验管理员、登录令牌和允许跨域来源的安全配置，缺少必要配置时阻止带错配置启动。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    @Valid
    private Jwt jwt = new Jwt();
    @Valid
    private Admin admin = new Admin();
    private Cors cors = new Cors();

    /** 保存登录令牌的签名密钥和有效时长，启动时校验密钥是否满足要求。 */
    @Data
    public static class Jwt {
        @NotBlank(message = "JWT_SECRET must be configured")
        @Size(min = 32, message = "JWT_SECRET must contain at least 32 characters")
        private String secret;
        private long expiration = 86400000;
    }

    /** 保存初始管理员账号和密码哈希，供没有数据库覆盖记录时验证登录。 */
    @Data
    public static class Admin {
        @NotBlank(message = "ADMIN_USERNAME must be configured")
        private String username;
        @NotBlank(message = "ADMIN_PASSWORD_HASH must be configured")
        @Pattern(regexp = "^\\$2[aby]\\$\\d{2}\\$.{53}$", message = "ADMIN_PASSWORD_HASH must be a BCrypt hash")
        private String passwordHash;
    }

    /** 指定允许从哪些浏览器站点调用本系统接口。 */
    @Data
    public static class Cors {
        /** 允许跨域的来源列表，生产环境应明确列出，避免使用通配符 */
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:8001",
                "http://127.0.0.1:8001"
        ));
    }
}
