package com.checker.service;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * 群晖文件上传服务：将本地下载好的漫画压缩包上传到 Komga 库目录。
 * 实现优先使用可写挂载目录，其次尝试 SMB/CIFS，再回退到 SFTP（SSH）。
 */
public interface SynologyUploadService {

    /**
     * 上传本地文件到群晖 Komga 库目录。
     *
     * @param localFile      本地文件
     * @param targetFilename 目标文件名（含 .cbz 扩展名）
     * @throws Exception 所有上传方式均失败时抛出
     */
    default void upload(Path localFile, String targetFilename) throws Exception {
        upload(localFile, "", targetFilename, bytes -> {
        });
    }

    /**
     * 上传文件并回调累计上传字节数，供长时间运行的 Temporal Activity 发送心跳。
     */
    default void upload(Path localFile, String targetFilename, LongConsumer progress) throws Exception {
        upload(localFile, "", targetFilename, progress);
    }

    /** 上传到配置归档根目录下的系列子目录，并通过回调报告累计上传字节数。 */
    void upload(Path localFile, String relativeDirectory, String targetFilename,
                LongConsumer progress) throws Exception;
}
