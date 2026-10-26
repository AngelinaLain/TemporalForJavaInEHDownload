package com.checker.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 文档的中文元数据与 JWT 认证定义。 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI galleryImportOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("图库导入服务 API")
                        .description("图库抓取、导入、去重、归档同步及 AI 配置的后端接口文档。")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("除登录接口外，请填写 JWT 访问令牌。")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
