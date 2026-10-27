package com.checker.temporalServices.activities.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.ErrorType;
import com.checker.clients.KomgaApiClient;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.config.EhNetworkConfig;
import com.checker.dto.KomgaBookMatchResult;
import com.checker.temporalServices.activities.KomgaActivity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.checker.common.EhNetworkClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 补齐画廊元数据，确认 Komga 中的物理书籍，并写入阅读元数据与入库状态。
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class KomgaActivityImpl implements KomgaActivity {

    @Autowired
    private EhNetworkConfig netConfig;

    @Autowired
    private EhNetworkClient ehNetworkClient;

    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private KomgaApiClient komgaApiClient;

    /**
     * Komga 的全文搜索面向展示元数据，不能保证找到物理文件名。
     * 确认时读取目标图库或系列的书籍快照，再按名称和路径精确匹配；短缓存合并并发轮询。
     */
    private final Cache<String, JSONArray> booksBySeriesCache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(Duration.ofSeconds(10))
            .build();

    @Override
    /** 向源站读取当前画廊的标签并更新数据库，供后续 ComicInfo 和阅读元数据使用。 */
    public void fetchAndSaveMetadata(Long gid, String token) {
        String jsonBody = String.format("{\"method\":\"gdata\",\"gidlist\":[[%d,\"%s\"]],\"namespace\":1}", gid, token);

        String response = ehNetworkClient.postJson(Constants.EHENTAI_API_URL, jsonBody);

        JSONObject resObj = JSONUtil.parseObj(response);
        JSONArray gmetadata = resObj.getJSONArray("gmetadata");
        if (gmetadata != null && !gmetadata.isEmpty()) {JSONObject metadata = gmetadata.getJSONObject(0);
            if (metadata.containsKey("tags") && !metadata.isNull("tags")) {
                JSONArray tagsArray = metadata.getJSONArray("tags");
                List<String> tagsList = tagsArray.toList(String.class);
                EhGalleriesEntity updateEntity = new EhGalleriesEntity();
                updateEntity.setGid(gid);
                updateEntity.setTags(tagsList);
                galleriesMapper.updateById(updateEntity);
                log.info("✅ GID: {} 元数据存库成功，共 {} 个标签", gid, tagsList.size());
            }else{
                log.warn("⚠ GID: {} 元数据为空",gid);
            }
        }
    }

    @Override
    /** 保留旧流程的全文搜索入口，命中首条结果即返回；新入库确认使用物理身份精确匹配方法。 */
    public String findBookInKomga(Long gid) {
        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/list";
        JSONObject searchBody = new JSONObject();
        searchBody.set("fullTextSearch", String.valueOf(gid));
        JSONObject condition = new JSONObject();
        String libraryId = netConfig.getKomga().getLibraryId();
        if (StrUtil.isNotBlank(libraryId)) {
            JSONObject libraryCond = new JSONObject();
            libraryCond.set("operator", "is");
            libraryCond.set("value", libraryId);
            condition.set("libraryId", libraryCond);
        }
        searchBody.set("condition", condition);

        HttpResponse response = HttpRequest.post(komgaUrl)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute();

        if (response.isOk()) {
            JSONObject resObj = JSONUtil.parseObj(response.body());
            JSONArray content = resObj.getJSONArray("content");
            if (content != null && !content.isEmpty()) {
                String bookId = content.getJSONObject(0).getStr("id");
                log.info("🎯 Komga 命中! GID: {}, BookID: {}", gid, bookId);
                return bookId;
            }
            log.warn("⚠️ 在目标 Komga Library 中未找到 GID: {}", gid);
        } else {
            log.error("❌ Komga 搜索失败: HTTP {}", response.getStatus());
        }
        return null;
    }

    @Override
    /** 读取目标图库的物理文件信息，只在唯一匹配时确认书籍，多个候选明确返回歧义。 */
    public KomgaBookMatchResult findExactBookInKomga(Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) {
            return KomgaBookMatchResult.notFound("数据库中不存在画廊记录");
        }

        try {
            JSONArray content = getBooksInTargetLibrary();
            List<String> matches = KomgaBookMatcher.exactBookIds(
                    content,
                    gid,
                    gallery.getFilename(),
                    null,
                    netConfig.getKomga().getLibraryId());
            if (matches.size() == 1) {
                String bookId = matches.get(0);
                log.info("🎯 Komga 精确命中! GID: {}, 文件: {}, BookID: {}",
                        gid, gallery.getFilename(), bookId);
                return KomgaBookMatchResult.found(bookId,
                        "数据库文件名或历史 [GID] 物理路径与目标 Library 唯一匹配");
            }
            if (matches.size() > 1) {
                String reason = "Komga 返回多个精确候选: " + matches;
                log.error("❌ {}, GID: {}, 文件: {}", reason, gid, gallery.getFilename());
                return KomgaBookMatchResult.ambiguous(reason, matches);
            }
            int candidateCount = content == null ? 0 : content.size();
            return KomgaBookMatchResult.notFound(String.format(
                    "扫描中，目标 Library 返回 %d 本书，未发现数据库文件名或 [%d] 物理路径匹配",
                    candidateCount, gid));
        } catch (ApplicationFailure e) {
            throw e;
        } catch (Exception e) {
            throw ApplicationFailure.newFailure(
                    "Komga 图书查询异常: " + e.getMessage(), ErrorType.KOMGA_SCAN_FAILED.getCode());
        }
    }


    @Override
    /** 写入前比较现有阅读元数据，更新成功后再保存 Book ID 并标记已入库。 */
    public void pushMetadataToKomga(String bookId, Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        Map<String, Object> metadata = komgaApiClient.buildBookMetadata(gallery);

        try {
            // 幂等操作：PATCH 前先 GET 比对，元数据一致则跳过，Temporal 重试不会产生重复标签
            komgaApiClient.patchBookMetadataIfChanged(bookId, metadata);
        } catch (Exception e) {
            log.error("❌ Komga PATCH 异常, GID: {}, BookID: {}", gid, bookId, e);
            throw ApplicationFailure.newFailure(
                    "Komga metadata patch exception: " + e.getMessage(), ErrorType.KOMGA_METADATA_PATCH_EXCEPTION.getCode());
        }

        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setKomgaBookId(bookId);
        updateEntity.setDownloadStatus(DownloadStatus.IMPORTED.getValue());
        galleriesMapper.updateById(updateEntity);
        log.info("🎉 GID: {} 已成功打上标签并入库! BookID: {}", gid, bookId);
    }


    @Override
    /** 请求 Komga 扫描图库，并清理短期书籍缓存，让后续轮询能够看到新结果。 */
    public void triggerKomgaLibraryScan() {
        String libraryId = netConfig.getKomga().getLibraryId();
        if (StrUtil.isBlank(libraryId)) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "未配置 Komga Library ID", ErrorType.KOMGA_SCAN_FAILED.getCode());
        }
        String scanUrl = netConfig.getKomga().getUrl() + "/api/v1/libraries/" + libraryId + "/scan";
        try (HttpResponse response = HttpRequest.post(scanUrl)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .execute()) {
            if (response.isOk() || response.getStatus() == 202) {
                booksBySeriesCache.invalidateAll();
                log.info("🚀 Komga 扫描指令已发送，Library ID: {}", libraryId);
                return;
            }
            throw komgaHttpFailure("触发 Komga 扫描失败", response.getStatus());
        }
    }

    /** 将 HTTP 失败转换成工作流可识别的错误类别，鉴权失败无需重复请求。 */
    private ApplicationFailure komgaHttpFailure(String action, int status) {
        String message = action + ", HTTP: " + status;
        if (status == 401 || status == 403) {
            return ApplicationFailure.newNonRetryableFailure(message, ErrorType.KOMGA_SCAN_FAILED.getCode());
        }
        return ApplicationFailure.newFailure(message, ErrorType.KOMGA_SCAN_FAILED.getCode());
    }

    /** 按图库和系列共用短期书籍列表，合并并发流程的重复查询。 */
    private JSONArray getBooksInTargetSeries(String targetSeriesId) {
        String libraryId = StrUtil.blankToDefault(netConfig.getKomga().getLibraryId(), "");
        String cacheKey = libraryId + "|" + targetSeriesId;
        return booksBySeriesCache.get(cacheKey, ignored -> queryBooksInTargetSeries(targetSeriesId));
    }

    /** 读取目标图库的书籍列表并短期复用，供物理文件唯一匹配使用。 */
    private JSONArray getBooksInTargetLibrary() {
        String libraryId = StrUtil.blankToDefault(netConfig.getKomga().getLibraryId(), "");
        return booksBySeriesCache.get("library|" + libraryId, ignored -> {
            String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/list?unpaged=true";
            JSONObject searchBody = new JSONObject();
            if (StrUtil.isNotBlank(libraryId)) {
                JSONObject libraryCondition = new JSONObject();
                libraryCondition.set("operator", "is");
                libraryCondition.set("value", libraryId);
                JSONObject condition = new JSONObject();
                condition.set("libraryId", libraryCondition);
                searchBody.set("condition", condition);
            }
            try (HttpResponse response = HttpRequest.post(komgaUrl)
                    .header("X-API-Key", netConfig.getKomga().getApiKey())
                    .header("Content-Type", "application/json")
                    .body(searchBody.toString()).execute()) {
                if (!response.isOk()) throw komgaHttpFailure("Komga Library 图书查询失败", response.getStatus());
                JSONArray content = JSONUtil.parseObj(response.body()).getJSONArray("content");
                return content == null ? new JSONArray() : content;
            }
        });
    }

    /** 只读取指定系列内的物理书籍，失败时交由上层工作流处理。 */
    private JSONArray queryBooksInTargetSeries(String targetSeriesId) {
        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/list?unpaged=true";
        JSONObject seriesIdCond = new JSONObject();
        seriesIdCond.set("operator", "is");
        seriesIdCond.set("value", targetSeriesId);
        JSONObject condition = new JSONObject();
        condition.set("seriesId", seriesIdCond);
        JSONObject searchBody = new JSONObject();
        searchBody.set("condition", condition);

        try (HttpResponse response = HttpRequest.post(komgaUrl)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute()) {
            if (!response.isOk()) {
                throw komgaHttpFailure("Komga 目标系列图书查询失败", response.getStatus());
            }
            JSONArray content = JSONUtil.parseObj(response.body()).getJSONArray("content");
            JSONArray result = content == null ? new JSONArray() : content;
            log.debug("Komga 目标系列物理文件索引已刷新，Series ID: {}, Book 数: {}",
                    targetSeriesId, result.size());
            return result;
        }
    }


    /**
     * 在目标图库内按展示标题或原始系列名查找完全一致的系列。
     * 没有匹配时返回空值，不将模糊搜索的第一项当成目标。
     *
     * @param seriesName 目标系列名称
     * @return 匹配的 seriesId，未找到返回 null
     */
    private String getSeriesIdByName(String seriesName) {
        String url = netConfig.getKomga().getUrl() + "/api/v1/series/list?size=50";
        JSONObject searchBody = new JSONObject();
        searchBody.set("fullTextSearch", seriesName);
        String libraryId = netConfig.getKomga().getLibraryId();
        if (StrUtil.isNotBlank(libraryId)) {
            JSONObject libraryCondition = new JSONObject();
            libraryCondition.set("operator", "is");
            libraryCondition.set("value", libraryId);
            JSONObject condition = new JSONObject();
            condition.set("libraryId", libraryCondition);
            searchBody.set("condition", condition);
        }

        try (HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute()) {
            if (!response.isOk()) {
                throw komgaHttpFailure("Komga 系列查询失败", response.getStatus());
            }

            JSONObject resObj = JSONUtil.parseObj(response.body());
            JSONArray content = resObj.getJSONArray("content");
            if (content == null || content.isEmpty()) return null;

            for (int i = 0; i < content.size(); i++) {
                JSONObject series = content.getJSONObject(i);
                if (!matchesConfiguredLibrary(series, libraryId)) continue;
                String title = series.getByPath("metadata.title", String.class);
                if (seriesName.equalsIgnoreCase(StrUtil.blankToDefault(title, ""))) {
                    return series.getStr("id");
                }
            }
            for (int i = 0; i < content.size(); i++) {
                JSONObject series = content.getJSONObject(i);
                if (!matchesConfiguredLibrary(series, libraryId)) continue;
                if (seriesName.equalsIgnoreCase(series.getStr("name"))) {
                    return series.getStr("id");
                }
            }
            return null;
        }
    }

    /** 有图库配置且响应也有图库 ID 时要求一致，兼容缺少此字段的历史响应。 */
    private boolean matchesConfiguredLibrary(JSONObject series, String libraryId) {
        String actualLibraryId = series.getStr("libraryId");
        return StrUtil.isBlank(libraryId)
                || StrUtil.isBlank(actualLibraryId)
                || libraryId.equals(actualLibraryId);
    }
}
