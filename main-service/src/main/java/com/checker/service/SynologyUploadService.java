package com.checker.service;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * 群晖文件上传服务：将本地下载好的漫画压缩包上传到 Komga 库目录。
 * 首选 SMB/CIFS，失败自动降级为 SFTP（SSH）。
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
        upload(localFile, targetFilename, bytes -> {
        });
    }

    /**
     * 上传文件并回调累计上传字节数，供长时间运行的 Temporal Activity 发送心跳。
     */
    void upload(Path localFile, String targetFilename, LongConsumer progress) throws Exception;
}
