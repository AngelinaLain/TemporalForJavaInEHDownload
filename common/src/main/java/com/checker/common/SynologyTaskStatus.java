package com.checker.common;

/**
 * 群晖 DownloadStation 任务状态枚举。
 * <p>
 * 替换原先散落在 SynologyActivityImpl、SingleGalleryDownloadWorkflowImpl 中的
 * 魔法字符串 "finished" / "error" / "downloading" 等。
 */
public enum SynologyTaskStatus {

    /** 下载完成 / 做种中 / 已解压 */
    FINISHED("finished"),
    /** 下载出错 / 任务损坏 / 文件丢失 */
    ERROR("error"),
    /** 下载进行中 */
    DOWNLOADING("downloading"),
    /** 群晖 BT 做种中 */
    SEEDING("seeding"),
    /** 群晖已解压归档文件 */
    EXTRACTED("extracted"),
    /** 群晖任务损坏 */
    BROKEN("broken"),
    /** 群晖文件未找到 */
    FILE_NOT_FOUND("file_not_found");

    private final String value;

    SynologyTaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 根据群晖 API 返回的原始状态字符串匹配枚举。
     * 匹配规则为值相等（忽略大小写），未命中返回 null。
     */
    public static SynologyTaskStatus fromApiStatus(String apiStatus) {
        if (apiStatus == null) return null;
        for (SynologyTaskStatus s : values()) {
            if (s.value.equalsIgnoreCase(apiStatus.trim())) {
                return s;
            }
        }
        return null;
    }

    /**
     * 判断该状态是否属于"已完成"类（下载成功可取文件）。
     */
    public boolean isCompleted() {
        return this == FINISHED || this == SEEDING || this == EXTRACTED;
    }

    /**
     * 判断该状态是否属于"出错"类（需要重试或放弃）。
     */
    public boolean isError() {
        return this == ERROR || this == BROKEN || this == FILE_NOT_FOUND;
    }
}
