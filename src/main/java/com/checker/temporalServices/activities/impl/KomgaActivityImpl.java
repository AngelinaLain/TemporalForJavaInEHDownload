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
import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.temporalServices.activities.KomgaActivity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Komga Activity 实现：负责与 Komga 漫画库服务交互（元数据拉取、图书搜索、标签推送、库扫描）
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class KomgaActivityImpl implements KomgaActivity {

    @Autowired
    private EhNetworkConfig netConfig;

    @Autowired
    private EhGalleriesMapper galleriesMapper;

    /** Komga 系列 ID 缓存：目标系列 ID 在 Komga 中基本固定不变，缓存后永久复用，
     *  避免每次 findBookInKomga 轮询时重复查询 series/list 接口 */
    private final Cache<String, String> seriesIdCache = Caffeine.newBuilder()
            .maximumSize(10)
            .build();

    // ------------------------------------------------------------------ fetchAndSaveMetadata

    @Override
    public void fetchAndSaveMetadata(Long gid, String token) {
        String jsonBody = String.format("{\"method\":\"gdata\",\"gidlist\":[[%d,\"%s\"]],\"namespace\":1}", gid, token);

        String response = HttpRequest.post(Constants.EHENTAI_API_URL).body(jsonBody).execute().body();

        JSONObject resObj = JSONUtil.parseObj(response);
        JSONArray gmetadata = resObj.getJSONArray("gmetadata");
        if (gmetadata != null && !gmetadata.isEmpty()) {
            JSONArray tagsArray = gmetadata.getJSONObject(0).getJSONArray("tags");
            List<String> tagsList = tagsArray.toList(String.class);
            EhGalleriesEntity updateEntity = new EhGalleriesEntity();
            updateEntity.setGid(gid);
            updateEntity.setTags(tagsList);
            galleriesMapper.updateById(updateEntity);
            log.info("✅ GID: {} 元数据存库成功，共 {} 个标签", gid, tagsList.size());
        }
    }

    // ------------------------------------------------------------------ findBookInKomga

    @Override
    public String findBookInKomga(Long gid) {
        String targetSeriesId = getOrCacheTargetSeriesId();
        if (StrUtil.isBlank(targetSeriesId)) {
            log.error("❌ 无法在 Komga 中找到 [{}] 系列", Constants.KOMGA_TARGET_SERIES);
            return null;
        }

        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/list";
        JSONObject searchBody = new JSONObject();
        searchBody.set("fullTextSearch", String.valueOf(gid));
        JSONObject seriesIdCond = new JSONObject();
        seriesIdCond.set("operator", "is");
        seriesIdCond.set("value", targetSeriesId);
        JSONObject condition = new JSONObject();
        condition.set("seriesId", seriesIdCond);
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
            log.warn("⚠️ 在系列 [N8N_Update] 中未找到 GID: {}", gid);
        } else {
            log.error("❌ Komga 搜索失败: HTTP {}", response.getStatus());
        }
        return null;
    }

    // ------------------------------------------------------------------ pushMetadataToKomga

    @Override
    public void pushMetadataToKomga(String bookId, Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        JSONObject metadata = new JSONObject();
        metadata.set("title", gallery.getTitle());
        metadata.set("titleLock", true);
        if (gallery.getTags() != null && !gallery.getTags().isEmpty()) {
            metadata.set("tags", gallery.getTags());
            metadata.set("tagsLock", true);
        }

        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/" + bookId + "/metadata";

        try {
            OkHttpClient client = new OkHttpClient();
            okhttp3.RequestBody body = okhttp3.RequestBody.create(metadata.toString(), okhttp3.MediaType.parse("application/json"));

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(komgaUrl)
                    .patch(body) // OkHttp 原生支持 PATCH
                    .header("X-API-Key", netConfig.getKomga().getApiKey())
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int status = response.code();
                if (!(status == 204 || (status >= 200 && status < 300))) {
                    throw ApplicationFailure.newFailure(
                            "Komga metadata patch failed: HTTP " + status, ErrorType.KOMGA_METADATA_PATCH_FAILED.getCode());
                }
            }
        } catch (ApplicationFailure e) {
            throw e;
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

    // ------------------------------------------------------------------ triggerKomgaLibraryScan

    @Override
    public void triggerKomgaLibraryScan() {
        String libraryId = netConfig.getKomga().getLibraryId();
        if (StrUtil.isBlank(libraryId)) {
            log.warn("⚠️ 未配置 Komga Library ID，跳过扫描触发。");
            return;
        }
        String scanUrl = netConfig.getKomga().getUrl() + "/api/v1/libraries/" + libraryId + "/scan";
        HttpResponse response = HttpRequest.post(scanUrl)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .execute();
        if (response.isOk() || response.getStatus() == 202) {
            log.info("🚀 Komga 扫描指令已发送，Library ID: {}", libraryId);
        } else {
            log.error("❌ 触发 Komga 扫描失败, HTTP: {}", response.getStatus());
        }
    }

    // ------------------------------------------------------------------ private helpers

    /**
     * 懒加载并缓存目标系列 ID，只在第一次访问时查询 Komga，后续复用缓存值。
     * 若查询结果为 null（系列不存在），不会缓存，下次调用会重新查询。
     */
    private String getOrCacheTargetSeriesId() {
        String cached = seriesIdCache.getIfPresent(Constants.KOMGA_TARGET_SERIES);
        if (cached != null) return cached;

        String seriesId = getSeriesIdByName(Constants.KOMGA_TARGET_SERIES);
        if (seriesId != null) {
            seriesIdCache.put(Constants.KOMGA_TARGET_SERIES, seriesId);
        }
        return seriesId;
    }

    /**
     * 根据系列名称在 Komga 中查找对应的 seriesId
     *
     * @param seriesName 目标系列名称
     * @return 匹配的 seriesId，未找到返回 null
     */
    private String getSeriesIdByName(String seriesName) {
        String url = netConfig.getKomga().getUrl() + "/api/v1/series/list?size=50";
        JSONObject searchBody = new JSONObject();
        searchBody.set("fullTextSearch", seriesName);

        HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute();

        if (!response.isOk()) {
            log.error("❌ Komga series/list 查询失败: HTTP {}", response.getStatus());
            return null;
        }

        JSONObject resObj = JSONUtil.parseObj(response.body());
        JSONArray content = resObj.getJSONArray("content");
        if (content == null || content.isEmpty()) return null;

        for (int i = 0; i < content.size(); i++) {
            JSONObject series = content.getJSONObject(i);
            String title = series.getByPath("metadata.title", String.class);
            if (seriesName.equalsIgnoreCase(StrUtil.blankToDefault(title, ""))) {
                return series.getStr("id");
            }
        }
        for (int i = 0; i < content.size(); i++) {
            if (seriesName.equalsIgnoreCase(content.getJSONObject(i).getStr("name"))) {
                return content.getJSONObject(i).getStr("id");
            }
        }
//        return content.getJSONObject(0).getStr("id");
        return null;
    }
}
