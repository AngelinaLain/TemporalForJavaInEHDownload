package com.checker.temporalServices.activities;

import com.checker.dto.KomgaBookMatchResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Komga 域 Activity：负责 Komga 图书馆扫描与元数据管理
 */
@ActivityInterface
public interface KomgaActivity {

    /**
     * 调用 EHentai gdata API 获取标签并存入数据库
     */
    @ActivityMethod
    void fetchAndSaveMetadata(Long gid, String token);

    /**
     * 在 Komga 中按 GID + SeriesID 精确查找 BookID
     *
     * @return BookID（未找到时返回 null）
     */
    @ActivityMethod
    String findBookInKomga(Long gid);

    /**
     * 按数据库文件名、GID、目标系列和目标库做唯一匹配。
     */
    @ActivityMethod
    KomgaBookMatchResult findExactBookInKomga(Long gid);

    /**
     * PATCH 元数据（标题、标签）到 Komga，并将 download_status 更新为"已入库"
     */
    @ActivityMethod
    void pushMetadataToKomga(String bookId, Long gid);

    /**
     * 触发 Komga 图书馆主动扫描
     */
    @ActivityMethod
    void triggerKomgaLibraryScan();
}
