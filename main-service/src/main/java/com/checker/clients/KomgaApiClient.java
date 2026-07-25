package com.checker.clients;

import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
public class KomgaApiClient {

    @Autowired
    private EhNetworkConfig netConfig;

    private OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private String baseUrl;
    @PostConstruct
    public void init() {
        // 1. 防御性编程：去掉 yaml 配置中 URL 末尾可能多带的斜杠
        this.baseUrl = netConfig.getKomga().getUrl();
        if (this.baseUrl != null && this.baseUrl.endsWith("/")) {
            this.baseUrl = this.baseUrl.substring(0, this.baseUrl.length() - 1);
        }

        // 2. 【关键修复】使用 Interceptor 代替 Authenticator 主动注入 Header
        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("X-API-Key", netConfig.getKomga().getApiKey())
                            .build();
                    return chain.proceed(request);
                })
                .build();
    }

    /**
     * 根据 Tag 搜索画廊 (Series) ID 列表 
     */
    public List<String> findSeriesIdsByTag(String tag) throws Exception {
        // 【关键修复】对查询参数进行 URL 编码
        String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
        String url = baseUrl + "/api/v1/series?search=" + encodedTag;

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // 优化错误日志，将后端的报错 body 一并打印
                log.error("获取Series失败, HTTP {}, body: {}", response.code(), response.body() != null ? response.body().string() : "");
                throw new RuntimeException("获取Series失败: " + response.code());
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode content = root.get("content");
            List<String> ids = new ArrayList<>();
            if (content != null && content.isArray()) {
                for (JsonNode node : content) {
                    ids.add(node.get("id").asText());
                }
            }
            return ids;
        }
    }

    /**
     * 查找是否存在指定名称的合集 (Collection)
     */
    public JsonNode findCollectionByName(String name) throws Exception {
        // 【关键修复】合集名称同样需要 URL 编码
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String url = baseUrl + "/api/v1/collections?search=" + encodedName;

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;

            JsonNode root = null;
            if (response.body() != null) {
                root = objectMapper.readTree(response.body().string());
            }
            JsonNode content = null;
            if (root != null) {
                content = root.get("content");
            }
            if (content != null && content.isArray() && !content.isEmpty()) {
                return content.get(0);
            }
            return null;
        }
    }

    /**
     * 创建新合集
     */
    public void createCollection(String name, List<String> seriesIds) throws Exception {
        String url = baseUrl + "/api/v1/collections";
        String json = objectMapper.writeValueAsString(Map.of("name", name, "ordered", false, "seriesIds", seriesIds));
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder().url(url).post(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("创建合集失败: " + response.code() + " " + (response.body() != null ? response.body().string() : ""));
            }
        }
    }

    /**
     * 局部更新现有的合集 (追加画廊)
     */
    public void updateCollection(String collectionId, List<String> seriesIds) throws Exception {
        String url = baseUrl + "/api/v1/collections/" + collectionId;
        String json = objectMapper.writeValueAsString(Map.of("seriesIds", seriesIds));
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder().url(url).patch(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("更新合集失败: " + response.code() + " " + (response.body() != null ? response.body().string() : ""));
            }
        }
    }

    /**
     * 根据 bookId 获取图书详情 (为了拿到所属的 seriesId)
     */
    public JsonNode getBook(String bookId) throws Exception {
        String url = baseUrl + "/api/v1/books/" + bookId;
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;
            return objectMapper.readTree(response.body().string());
        }
    }

    /**
     * 更新图书 (Book) 的元数据标签
     */
    public void updateBookTags(String bookId, List<String> tags) throws Exception {
        String url = baseUrl + "/api/v1/books/" + bookId + "/metadata";
        // 传入 tags 数组
        String json = objectMapper.writeValueAsString(Map.of("tags", tags));
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder().url(url).patch(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("更新 Book [{}] 标签失败: HTTP {}", bookId, response.code());
            }
        }
    }

    /**
     * 更新系列 (Series) 的元数据标签 (这个最重要，决定了能不能在主页过滤搜到！)
     */
    public void updateSeriesTags(String seriesId, List<String> tags) throws Exception {
        String url = baseUrl + "/api/v1/series/" + seriesId + "/metadata";
        String json = objectMapper.writeValueAsString(Map.of("tags", tags));
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder().url(url).patch(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("更新 Series [{}] 标签失败: HTTP {}", seriesId, response.code());
            }
        }
    }

    /**
     * 从画廊实体构建 Komga Book 元数据（标题、标签、作者）
     */
    public Map<String, Object> buildBookMetadata(EhGalleriesEntity gallery) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", gallery.getTitle());
        metadata.put("titleLock", true);

        if (gallery.getTags() != null && !gallery.getTags().isEmpty()) {
            List<String> cleanTags = new ArrayList<>();
            List<Map<String, String>> authors = new ArrayList<>();

            for (String tag : gallery.getTags()) {
                if (tag.startsWith("artist:") || tag.startsWith("group:")) {
                    String artistName = tag.substring(tag.indexOf(':') + 1);
                    authors.add(Map.of("name", artistName, "role", "writer"));
                } else {
                    cleanTags.add(tag);
                }
            }

            metadata.put("tags", cleanTags);
            metadata.put("tagsLock", true);

            if (!authors.isEmpty()) {
                metadata.put("authors", authors);
                metadata.put("authorsLock", true);
            }
        }

        if (gallery.getSummary() != null && !gallery.getSummary().isBlank()) {
            metadata.put("summary", gallery.getSummary());
            metadata.put("summaryLock", true);
        }

        return metadata;
    }

    /**
     * PATCH Book 完整元数据到 Komga（标题 + 标签 + 作者）
     */
    public void patchBookMetadata(String bookId, Map<String, Object> metadata) throws Exception {
        String url = baseUrl + "/api/v1/books/" + bookId + "/metadata";
        String json = objectMapper.writeValueAsString(metadata);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder().url(url).patch(body).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Komga metadata patch failed: HTTP " + response.code());
            }
        }
    }
}
