package com.checker.temporalServices.activities;

import com.checker.common.SynologyTaskStatus;
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
     * 通过 FileStation API（失败则 SSH）对物理文件重命名为 [GID] 标题格式
     *
     * @param oldFilename 群晖下载生成的原始文件名（含后缀）
     * @return 重命名后的新文件名
     */
    @ActivityMethod
    String renameSynologyFile(Long gid, String oldFilename);
}
