package com.checker.common;

/**
 * Temporal ApplicationFailure 错误类型枚举
 */
public enum ErrorType {
    /**
     * 邮件发送失败
     */
    EMAIL_SEND_FAILED,
    /**
     * Komga元数据补丁失败
     */
    KOMGA_METADATA_PATCH_FAILED,
    /**
     * Komga元数据补丁异常
     */
    KOMGA_METADATA_PATCH_EXCEPTION,
    /**
     * Synology创建失败
     */
    SYNOLOGY_CREATE_FAILED,
    /**
     * Synology验证失败
     */
    SYNOLOGY_AUTH_FAILED,
    /**
     * Synology API错误
     */
    SYNOLOGY_API_ERROR,
    /**
     * 网络错误
     */
    NETWORK_ERROR,
    /**
     * 配额超出了
     */
    QUOTA_EXCEEDED,
    /**
     * IP禁止
     */
    IP_BANNED,
    /**
     * 存档链接提取失败
     */
    ARCHIVE_LINK_EXTRACT_FAILED;

    public String getCode() {
        return name();
    }
}
