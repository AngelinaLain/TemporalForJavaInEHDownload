package com.checker.common;

/**
 * 列出画廊从待下载、下载中到入库或失败的各个状态，并提供数据库使用的中文名称。
 */
public enum DownloadStatus {
    PENDING("未下载"),
    DOWNLOADING("下载中"),
    DOWNLOADED("已下载"),
    WAITING_KOMGA("等待 Komga"),
    PARTIAL("不完整"),
    DOWNLOAD_FAILED("下载失败"),
    KOMGA_IMPORT_FAILED("Komga 入库失败"),
    IMPORTED("已入库"),
    REVIEW_REQUIRED("待去重审核"),
    BLOCKED("阻断"),
    IGNORED("已忽略");

    private final String value;

    DownloadStatus(String value) {
        this.value = value;
    }

    /**
     * 获取状态的中文文本值
     *
     * @return 状态中文描述
     */
    public String getValue() {
        return value;
    }
}
