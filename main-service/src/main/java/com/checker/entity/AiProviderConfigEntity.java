package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ai_provider_configs")
public class AiProviderConfigEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String scope;
    private String protocol;
    private String baseUrl;
    private String apiKeyEncrypted;
    private String defaultModel;
    private Boolean allowTextMetadata;
    private Boolean allowVisualInput;
    private Integer requestTimeoutSeconds;
    private Integer maxConcurrency;
    private Boolean enabled;
    private Date createdAt;
    private Date updatedAt;
}
