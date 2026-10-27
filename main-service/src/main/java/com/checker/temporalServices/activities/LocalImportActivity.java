package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 定义本地归档下载、写入阅读元数据并发布到目标图库的后台操作。
 */
@ActivityInterface
public interface LocalImportActivity {

    /**
     * 本地下载并注入元数据后上传到群晖。
     *
     * @param downloadUrl EHentai 归档直链
     * @param gid         画廊 ID（用于加载标题/标签/简介等元数据）
     * @param sizeMb      预估文件大小，用于日志和进度说明
     */
    @ActivityMethod
    void localDownloadAndImport(String downloadUrl, Long gid, Double sizeMb);
}
