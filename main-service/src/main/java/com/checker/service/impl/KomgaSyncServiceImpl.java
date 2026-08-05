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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KomgaSyncServiceImpl implements KomgaSyncService {
    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private KomgaApiClient komgaApiClient;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private TaskExecutor backgroundTaskExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void startTagSync() {
        backgroundTaskExecutor.execute(this::syncTagsToKomga);
    }

    /**
     * 补偿机制：将数据库中的 tags 同步覆盖到 Komga 中。
     * 由 startTagSync 在受控的后台线程中调用。
     */
    @Override
    public void syncTagsToKomga() {
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNotNull("komga_book_id")
                .ne("komga_book_id", "")
                .isNotNull("tags");

        List<EhGalleriesEntity> galleries = galleriesMapper.selectList(queryWrapper);
        log.info("开始同步，共找到 {} 条需要补全标签的记录", galleries.size());

        for (EhGalleriesEntity gallery : galleries) {
            try {
                String bookId = gallery.getKomgaBookId();
                List<String> tags = objectMapper.readValue(gallery.getTags().toString(), new TypeReference<List<String>>() {});
                if (tags == null || tags.isEmpty()) {
                    continue;
                }

                JsonNode bookNode = komgaApiClient.getBook(bookId);
                if (bookNode == null) {
                    log.warn("在 Komga 中找不到 BookId [{}], 可能已被手动删除", bookId);
                    continue;
                }

                String seriesId = bookNode.get("seriesId").asText();
                komgaApiClient.updateBookTags(bookId, tags);
                komgaApiClient.updateSeriesTags(seriesId, tags);
                log.info("成功同步标签到 Komga -> BookId: {}, SeriesId: {}, Tags数量: {}", bookId, seriesId, tags.size());
            } catch (Exception e) {
                log.error("同步画廊 [{}] 的标签时发生异常: {}", gallery.getGid(), e.getMessage());
            }
        }
        log.info("所有的 Komga 标签同步任务已完成");
    }

    @Override
    public void batchRefreshAllKomgaMetadata() {
        backgroundTaskExecutor.execute(() -> {
            QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
            queryWrapper.isNotNull("komga_book_id").ne("komga_book_id", "");
            List<EhGalleriesEntity> importedGalleries = galleriesMapper.selectList(queryWrapper);
            log.info("开始后台批量刷新 Komga 元数据，共查找到 {} 本已关联书籍", importedGalleries.size());

            int successCount = 0;
            for (EhGalleriesEntity gallery : importedGalleries) {
                try {
                    Map<String, Object> metadata = komgaApiClient.buildBookMetadata(gallery);
                    komgaApiClient.patchBookMetadata(gallery.getKomgaBookId(), metadata);
                    successCount++;
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("批量刷新 Komga 元数据被中断");
                    break;
                } catch (Exception e) {
                    log.error("刷新元数据失败, GID: {}, BookID: {}", gallery.getGid(), gallery.getKomgaBookId(), e);
                }
            }
            log.info("批量刷新元数据任务完成，成功刷新 {}/{} 本书籍", successCount, importedGalleries.size());
        });
    }
}
