package com.checker.dto;

import com.checker.common.SynologyTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群晖下载等待结果：由长轮询 Activity（waitForDownloadComplete）返回，
 * 携带最终任务状态与物理文件实际大小（MB），供工作流做 PARTIAL 完整性校验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SynologyDownloadResult {
    private SynologyTaskStatus status;
    private Double actualSizeMb;
    private String filename;
}
