package com.checker.dto;

import com.checker.common.SynologyTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 返回群晖下载等待的最终状态、实际文件体积和文件名，供工作流检查下载是否完整。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SynologyDownloadResult {
    private SynologyTaskStatus status;
    private Double actualSizeMb;
    private String filename;
}
