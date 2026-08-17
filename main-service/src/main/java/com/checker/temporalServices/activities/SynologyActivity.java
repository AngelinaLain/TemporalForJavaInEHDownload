package com.checker.temporalServices.activities;

import com.checker.common.SynologyTaskStatus;
import com.checker.dto.SynologyDownloadResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 群晖域 Activity：负责与 Synology DownloadStation / FileStation 交互
 */
@ActivityInterface
public interface SynologyActivity {

    /**
     * 将下载任务推送给 Synology DownloadStation
     *
     * @param downloadUrl 下载直链
     * @param gid         EHentai 画廊 ID（用于后续状态追踪）
     * @param destination 可选存储路径，null 时使用配置默认值
     * @return gid（用于后续任务匹配）
     */
    @ActivityMethod
    Long pushToSynology(String downloadUrl, Long gid, String destination);

    /**
     * 通过 URI 匹配轮询群晖任务状态
     *
     * @return {@link SynologyTaskStatus} 枚举值
     */
    @ActivityMethod
    SynologyTaskStatus checkSynologyTaskStatus(Long gid, String downloadUrl);

    /**
     * 长轮询等待下载完成：把原工作流里的 Workflow.sleep + 状态轮询下沉到 Activity 内部，
     * 通过心跳向 Temporal UI 实时上报进度。
     * <p>
     * 超时与重试策略由工作流侧的 ActivityOptions 指定（WorkflowSteps.SYNO_LONG_OPTIONS）：
     * StartToCloseTimeout 48 小时 + HeartbeatTimeout 5 分钟，
     * 认证/配额/封禁/伪装文件类致命错误直接标记不可重试。
     *
     * @param gid                  画廊 ID
     * @param downloadUrl          下载直链（用于任务匹配）
     * @param estimatedWaitSeconds 按预估大小计算的首次等待秒数
     * @return 最终任务状态与物理文件实际大小
     */
    @ActivityMethod
    SynologyDownloadResult waitForDownloadComplete(Long gid, String downloadUrl, long estimatedWaitSeconds);

    /**
     * 通过 FileStation API（失败则 SSH）对物理文件重命名为 [GID] 标题格式
     *
     * @param oldFilename 群晖下载生成的原始文件名（含后缀）
     * @return 重命名后的新文件名
     */
    @ActivityMethod
    String renameSynologyFile(Long gid, String oldFilename);
}
