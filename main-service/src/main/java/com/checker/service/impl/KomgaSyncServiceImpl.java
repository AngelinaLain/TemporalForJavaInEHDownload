package com.checker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.clients.KomgaApiClient;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.KomgaSyncService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class KomgaSyncServiceImpl implements KomgaSyncService {
    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private KomgaApiClient komgaApiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 补偿机制：将数据库中的 tags 同步覆盖到 Komga 中
     */
    @Override
    public void syncTagsToKomga() {
        // 1. 查询所有 komga_book_id 和 tags 都不为空的记录
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNotNull("komga_book_id")
                .ne("komga_book_id", "")
                .isNotNull("tags");

        List<EhGalleriesEntity> galleries = galleriesMapper.selectList(queryWrapper);
        log.info("开始同步，共找到 {} 条需要补全标签的记录", galleries.size());

        for (EhGalleriesEntity gallery : galleries) {
            try {
                String bookId = gallery.getKomgaBookId();
                // 数据库里的 JSON 字符串反序列化为 List<String>
                List<String> tags = objectMapper.readValue(gallery.getTags().toString(), new TypeReference<List<String>>() {});

                if (tags == null || tags.isEmpty()) continue;

                // 2. 通过 bookId 查询 Komga，获取对应的 seriesId
                JsonNode bookNode = komgaApiClient.getBook(bookId);
                if (bookNode == null) {
                    log.warn("在 Komga 中找不到 BookId [{}], 可能已被手动删除", bookId);
                    continue;
                }

                String seriesId = bookNode.get("seriesId").asText();

                // 3. 将标签同时写入 Book 和 Series（确保各处都能检索到）
                komgaApiClient.updateBookTags(bookId, tags);
                komgaApiClient.updateSeriesTags(seriesId, tags);

                log.info("✅ 成功同步标签到 Komga -> BookId: {}, SeriesId: {}, Tags数量: {}", bookId, seriesId, tags.size());

            } catch (Exception e) {
                log.error("同步画廊 [{}] 的标签时发生异常: {}", gallery.getGid(), e.getMessage());
            }
        }
        log.info("🎉 所有的 Komga 标签同步任务已完成！");
    }

    @Override
    public void batchRefreshAllKomgaMetadata() {
        // 1. 查询所有已经成功入库且有 KomgaBookId 的画廊
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNotNull("komga_book_id");
        // 如果你需要过滤状态，可以加: queryWrapper.eq("download_status", DownloadStatus.IMPORTED.getValue());
        List<EhGalleriesEntity> importedGalleries = galleriesMapper.selectList(queryWrapper);
        log.info("🚀 开始后台批量刷新 Komga 元数据，共查找到 {} 本已关联书籍", importedGalleries.size());
        // 2. 放到异步线程中执行，防止阻塞 HTTP 请求
        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            for (EhGalleriesEntity gallery : importedGalleries) {
                try {
                    Map<String, Object> metadata = komgaApiClient.buildBookMetadata(gallery);
                    komgaApiClient.patchBookMetadata(gallery.getKomgaBookId(), metadata);
                    successCount++;
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.error("❌ 刷新元数据失败, GID: {}, BookID: {}", gallery.getGid(), gallery.getKomgaBookId(), e);
                }
            }
            log.info("🎉 批量刷新元数据任务彻底完成！成功刷新 {}/{} 本书籍", successCount, importedGalleries.size());
        });
    }
}
