package com.checker.temporalServices.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 本地导入 Activity：本地下载 EHentai 归档 → 注入 ComicInfo.xml → 上传群晖 Komga 库目录。
 * 超时/心跳策略由工作流侧 ActivityOptions（WorkflowSteps.LOCAL_IMPORT_OPTIONS）指定。
 */
@ActivityInterface
public interface LocalImportActivity {

    /**
     * 本地下载并注入元数据后上传到群晖。
     *
     * @param downloadUrl EHentai 归档直链
     * @param gid         画廊 ID（用于加载标题/标签/简介等元数据）
     * @param sizeMb      预估文件大小（仅日志用）
     */
    @ActivityMethod
    void localDownloadAndImport(String downloadUrl, Long gid, Double sizeMb);
}
