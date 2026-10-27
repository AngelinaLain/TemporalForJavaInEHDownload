package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 保存模型服务的连接地址、加密密钥和调用权限，供不同 AI 任务选择供应商。
 */
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
