package com.checker.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ArchiveDownloadInfo {
    private String downloadUrl;
    private Double estimatedSizeMb;

    /**
     * 兼容 Temporal 历史记录中的纯 String 返回值
     * 当 Jackson 发现历史记录里只有一段字符串时，会调用这个构造方法
     */
    @JsonCreator
    public ArchiveDownloadInfo(String downloadUrl) {
        this.downloadUrl = downloadUrl;
        this.estimatedSizeMb = 0.0; // 旧记录没有大小信息，给个兜底的默认值
    }
}
