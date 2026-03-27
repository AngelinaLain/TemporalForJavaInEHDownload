package com.checker.temporalServices.activities;

import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

@ActivityInterface
public interface EHAutomationActivity {
    /**
     * 1. 根据搜索条件抓取画廊列表（对标 JHenTai）
     * @param searchOptions 包含所有搜索参数的对象
     * @return 返回数据库实体类
     */
    @ActivityMethod
    List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions);

    /**
     * 2. 将数据保存到 MySQL (状态初始为 PENDING)
     * @param gallery 画廊
     */
    @ActivityMethod
    void saveToDatabase(EhGalleriesEntity gallery);

    /**
     * 3. 访问 archiver.php 解析最终下载直链
     * @param gid 画廊ID
     * @param token token
     * @return 返回下载页面
     */
    @ActivityMethod
    String extractDownloadUrl(Long gid, String token);

    /**
     * 4. 将任务推送给群晖 Download Station
     * 注：Synology create API 不返回 task_id，返回值为 GID（用于后续查询追踪）
     * @param downloadUrl 下载链接
     * @param gid EHentai 画廊 ID（用于任务关联）
     * @param destination 存储地址
     * @return 返回 GID（用于后续追踪任务）
     */
    @ActivityMethod
    Long pushToSynology(String downloadUrl, Long gid, String destination);

    /**
     * 5. 查询群晖的下载状态 (完成/下载中/失败)
     * 通过 URI 匹配而非 task_id（Synology create 不返回 task_id）
     * @param gid EHentai 画廊 ID（用于匹配任务 URI）
     * @param downloadUrl 下载链接（用于精确匹配）
     * @return 返回状态
     */
    @ActivityMethod
    String checkSynologyTaskStatus(Long gid, String downloadUrl);

    /**
     * 6. 更新数据库状态
     * @param gid 画廊ID
     * @param status 状态
     */
    @ActivityMethod
    void updateGalleryStatus(Long gid, String status);

    /**
     * 7. 发送邮件通知
     * @param subject 主题
     * @param content 内容
     */
    @ActivityMethod
    void sendEmailAlert(String subject, String content);

    /**
     * 获取下载失败的画廊
     * @return 返回画廊List
     */
    @ActivityMethod
    List<EhGalleriesEntity> getFailedGalleries();

    @ActivityMethod
    EhGalleriesEntity getGalleryById(Long gid);

    // 1. 获取并保存元数据
    @ActivityMethod
    void fetchAndSaveMetadata(Long gid, String token);

    // 2. 轮询查找 Komga 中的 Book ID
    @ActivityMethod
    String findBookInKomga(String title);

    // 3. 将元数据推送给 Komga
    @ActivityMethod
    void pushMetadataToKomga(String bookId, Long gid);

    @ActivityMethod
    void triggerKomgaLibraryScan();
}
