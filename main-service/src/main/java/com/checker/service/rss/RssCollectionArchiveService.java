package com.checker.service.rss;

import com.checker.common.DownloadStatus;
import com.checker.entity.EhGalleriesEntity;
import com.checker.dao.catalog.EhGalleriesMapper;
import com.checker.service.catalog.GalleryMediaService;
import com.checker.manager.collection.GallerySeriesPlacementService;
import com.checker.manager.komga.KomgaMediaClient;
import com.checker.service.komga.KomgaSeriesSyncService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** RSS 合集整理只移动现有归档；Komga 尚未看到目标路径时留给持久任务稍后确认。 */
@Service
public class RssCollectionArchiveService {
    private final KomgaSeriesSyncService seriesSync;
    private final GallerySeriesPlacementService placements;
    private final EhGalleriesMapper galleries;
    private final KomgaMediaClient komga;
    private final GalleryMediaService previews;

    public RssCollectionArchiveService(KomgaSeriesSyncService seriesSync,
                                       GallerySeriesPlacementService placements,
                                       EhGalleriesMapper galleries, KomgaMediaClient komga,
                                       GalleryMediaService previews) {
        this.seriesSync = seriesSync;
        this.placements = placements;
        this.galleries = galleries;
        this.komga = komga;
        this.previews = previews;
    }

    public boolean synchronize(long gid, long collectionId) throws Exception {
        seriesSync.synchronizeOne(gid, collectionId);
        EhGalleriesEntity gallery = galleries.selectById(gid);
        var placement = placements.resolve(gid);
        requireCurrent(gallery, placement, collectionId);
        String filename = gallery.getFilename();
        String directory = gallery.getStoragePath();
        String signature = gallery.getSeriesSyncSignature();
        String previousBookId = gallery.getKomgaBookId();
        previews.invalidate(gid);
        var found = komga.findBookAtPath(gid, directory, filename);
        if (found.isEmpty()) return false;

        // 远程索引读取期间用户可能重新归组；旧任务不能回写新布局的书籍关联。
        EhGalleriesEntity current = galleries.selectById(gid);
        requireCurrent(current, placements.resolve(gid), collectionId);
        if (!Objects.equals(filename, current.getFilename()) || !Objects.equals(directory, current.getStoragePath())
                || !Objects.equals(signature, current.getSeriesSyncSignature()))
            throw new IllegalStateException("归档位置已变化，等待重新确认 Komga 关联");
        int updated = galleries.compareAndSetKomgaBookId(gid, previousBookId, found.get(), filename, directory);
        if (updated != 1) throw new IllegalStateException("画廊关联已被其他任务修改，等待重新确认");
        previews.invalidate(gid);
        return true;
    }

    private static void requireCurrent(EhGalleriesEntity gallery,
                                       GallerySeriesPlacementService.SeriesPlacement placement,
                                       long collectionId) {
        if (gallery == null || (!DownloadStatus.IMPORTED.name().equals(gallery.getDownloadStatus())
                && !DownloadStatus.IMPORTED.getValue().equals(gallery.getDownloadStatus())))
            throw new IllegalStateException("画廊尚未确认入库，暂不更新合集关联");
        if (gallery.getDuplicateOfGid() != null || gallery.getSeriesCleanupFilename() != null
                || !Objects.equals(placement.collectionId(), collectionId)
                || !Objects.equals(placement.relativeDirectory(), gallery.getStoragePath())
                || !Objects.equals(placement.signature(), gallery.getSeriesSyncSignature()))
            throw new IllegalStateException("画廊合集归属或归档整理状态已变化，等待重新核对");
    }
}
