package com.checker.temporalServices.activities;

import com.checker.entity.EhGalleriesEntity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * 数据库域 Activity：负责所有对 eh_galleries 表的读写操作
 */
@ActivityInterface
public interface DatabaseActivity {

    /**
     * 幂等写入（存在则更新，不存在则插入）
     */
    @ActivityMethod
    void saveToDatabase(EhGalleriesEntity gallery);

    /**
     * 更新指定画廊的下载状态
     */
    @ActivityMethod
    void updateGalleryStatus(Long gid, String status);

    /**
     * 查询所有下载失败或已下载但未入库的画廊
     */
    @ActivityMethod
    List<EhGalleriesEntity> getFailedGalleries();

    /**
     * 按 GID 查询单条画廊记录
     */
    @ActivityMethod
    EhGalleriesEntity getGalleryById(Long gid);
}
