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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KomgaSyncServiceImpl implements KomgaSyncService {

    private static final String TAG_SYNC_KEY = "komga_tag_sync";
    private static final String METADATA_SYNC_KEY = "komga_metadata_sync";

    /** 批量 PATCH 的受限并发数 */
    private static final int BATCH_PATCH_CONCURRENCY = 4;

    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private KomgaApiClient komgaApiClient;

    @Autowired
    @Qualifier("backgroundTaskExecutor")
    private TaskExecutor backgroundTaskExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void startTagSync() {
        backgroundTaskExecutor.execute(this::syncTagsToKomga);
    }

    /**
     * 补偿机制：将数据库中的 tags 同步覆盖到 Komga 中。
     * 增量策略：基于 app_sync_checkpoint 中的时间戳，只处理 updated_at 晚于上次同步
     * （或从未同步）的记录；写入前先 GET 比对 Komga 现有标签，一致则跳过（Hash 增量比对）。
     * 由 startTagSync 在受控的后台线程中调用。
     */
    @Override
    public void syncTagsToKomga() {
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.isNotNull("komga_book_id")
                .ne("komga_book_id", "")
                .isNotNull("tags");

        Date lastSync = getCheckpoint(TAG_SYNC_KEY);
        if (lastSync != null) {
            queryWrapper.and(wrapper -> wrapper.gt("updated_at", lastSync).or().isNull("updated_at"));
            log.info("开始增量同步，只处理 updated_at > {} 或从未同步的记录", lastSync);
        } else {
            log.info("首次同步，进行全量比对");
        }

        List<EhGalleriesEntity> galleries = galleriesMapper.selectList(queryWrapper);
        log.info("开始同步，共找到 {} 条需要补全标签的记录", galleries.size());

        int skipped = 0;
        int synced = 0;
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

                // Hash 增量比对：现有标签与目标标签一致则跳过，避免无效 PATCH
                if (tagsMatch(bookNode, tags)) {
                    skipped++;
                    continue;
                }

                String seriesId = bookNode.get("seriesId").asText();
                komgaApiClient.updateBookTags(bookId, tags);
                komgaApiClient.updateSeriesTags(seriesId, tags);
                synced++;
                log.info("成功同步标签到 Komga -> BookId: {}, SeriesId: {}, Tags数量: {}", bookId, seriesId, tags.size());
            } catch (Exception e) {
                log.error("同步画廊 [{}] 的标签时发生异常: {}", gallery.getGid(), e.getMessage());
            }
        }
        saveCheckpoint(TAG_SYNC_KEY);
        log.info("Komga 标签同步完成: 同步 {} 条，跳过(已一致) {} 条", synced, skipped);
    }

    /**
     * 比对 Komga Book 中现有 tags 是否与目标 tags 完全一致。
     */
    private boolean tagsMatch(JsonNode bookNode, List<String> desiredTags) {
        JsonNode metadata = bookNode.get("metadata");
        if (metadata == null || metadata.get("tags") == null || !metadata.get("tags").isArray()) {
            return desiredTags.isEmpty();
        }
        List<String> existing = new ArrayList<>();
        metadata.get("tags").forEach(node -> existing.add(node.asText()));
        return existing.equals(desiredTags);
    }

    @Override
    public void batchRefreshAllKomgaMetadata() {
        backgroundTaskExecutor.execute(() -> {
            QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
            queryWrapper.isNotNull("komga_book_id").ne("komga_book_id", "");
            List<EhGalleriesEntity> importedGalleries = galleriesMapper.selectList(queryWrapper);
            log.info("开始后台批量刷新 Komga 元数据，共查找到 {} 本已关联书籍", importedGalleries.size());

            List<KomgaApiClient.BookMetadataPatch> patches = new ArrayList<>();
            for (EhGalleriesEntity gallery : importedGalleries) {
                Map<String, Object> metadata = komgaApiClient.buildBookMetadata(gallery);
                patches.add(new KomgaApiClient.BookMetadataPatch(gallery.getKomgaBookId(), metadata));
            }

            try {
                // 受限并发批量 PATCH（复用 HTTP keep-alive），替代逐本串行 + Thread.sleep
                List<String> succeeded = komgaApiClient.patchBookMetadataBatch(patches, BATCH_PATCH_CONCURRENCY);
                log.info("批量刷新元数据任务完成，成功刷新 {}/{} 本书籍", succeeded.size(), importedGalleries.size());
                saveCheckpoint(METADATA_SYNC_KEY);
            } catch (Exception e) {
                log.error("批量刷新元数据任务失败", e);
            }
        });
    }

    private Date getCheckpoint(String syncKey) {
        try {
            List<Date> rows = jdbcTemplate.query(
                    "SELECT last_synced_at FROM app_sync_checkpoint WHERE sync_key = ?",
                    (rs, rowNum) -> rs.getTimestamp("last_synced_at"),
                    syncKey);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("读取同步检查点失败（表可能尚未迁移）: {}", e.getMessage());
            return null;
        }
    }

    private void saveCheckpoint(String syncKey) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO app_sync_checkpoint (sync_key, last_synced_at) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE last_synced_at = VALUES(last_synced_at)",
                    syncKey, Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("保存同步检查点失败: {}", e.getMessage());
        }
    }
}
