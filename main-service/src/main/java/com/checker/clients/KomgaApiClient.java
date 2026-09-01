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

import java.io.IOException;
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

    /**
     * 幂等元数据更新：PATCH 前先 GET 比对现有元数据，若标题/标签/作者/摘要均一致则跳过写入。
     * 保证 Temporal 重试不会在 Komga 中产生重复标签或多余写操作。
     *
     * @return true 表示实际执行了 PATCH；false 表示元数据已一致，跳过
     */
    public boolean patchBookMetadataIfChanged(String bookId, Map<String, Object> metadata) throws Exception {
        JsonNode book = getBook(bookId);
        if (book == null) {
            log.warn("⚠️ Book [{}] 不存在于 Komga，跳过幂等校验并直接 PATCH", bookId);
            patchBookMetadata(bookId, metadata);
            return true;
        }
        if (metadataMatches(book, metadata)) {
            log.info("✅ Book [{}] 元数据已一致（标题/标签/作者/摘要均匹配），跳过 PATCH", bookId);
            return false;
        }
        patchBookMetadata(bookId, metadata);
        return true;
    }

    /**
     * 比对目标元数据与 Komga 中现有元数据是否一致。
     */
    private boolean metadataMatches(JsonNode book, Map<String, Object> desired) {
        JsonNode current = book.get("metadata");
        if (current == null) return false;

        String desiredTitle = (String) desired.get("title");
        String currentTitle = current.path("title").asText("");
        if (desiredTitle != null && !desiredTitle.equals(currentTitle)) return false;

        String desiredSummary = (String) desired.get("summary");
        String currentSummary = current.path("summary").asText("");
        if (desiredSummary != null && !desiredSummary.isBlank() && !desiredSummary.equals(currentSummary)) return false;

        if (desired.containsKey("tags")) {
            List<String> desiredTags = (List<String>) desired.get("tags");
            if (desiredTags != null && !jsonArrayEquals(current.get("tags"), desiredTags)) return false;
        }

        if (desired.containsKey("authors")) {
            List<Map<String, String>> desiredAuthors = (List<Map<String, String>>) desired.get("authors");
            if (desiredAuthors != null && !authorsMatch(current.get("authors"), desiredAuthors)) return false;
        }

        return true;
    }

    private static boolean jsonArrayEquals(JsonNode arrayNode, List<String> expected) {
        if (arrayNode == null || !arrayNode.isArray()) return expected.isEmpty();
        List<String> actual = new ArrayList<>();
        arrayNode.forEach(node -> actual.add(node.asText()));
        return actual.equals(expected);
    }

    private static boolean authorsMatch(JsonNode authorsNode, List<Map<String, String>> expected) {
        if (authorsNode == null || !authorsNode.isArray()) return expected.isEmpty();
        List<String> actual = new ArrayList<>();
        authorsNode.forEach(node -> {
            String name = node.path("name").asText("");
            String role = node.path("role").asText("");
            actual.add(name + "|" + role);
        });
        List<String> expectedNames = expected.stream()
                .map(a -> a.get("name") + "|" + a.getOrDefault("role", "writer"))
                .sorted()
                .toList();
        return actual.stream().sorted().toList().equals(expectedNames);
    }

    /**
     * 批量元数据更新请求项
     */
    public record BookMetadataPatch(String bookId, Map<String, Object> metadata) {}

    /**
     * 批量更新多本书元数据：复用同一个 OkHttpClient（HTTP keep-alive 连接池），
     * 以受限并发异步 PATCH，减少串行等待时间。单本失败不影响其他书。
     *
     * @return 成功更新的 bookId 列表
     */
    public List<String> patchBookMetadataBatch(List<BookMetadataPatch> patches, int maxConcurrency) throws Exception {
        if (patches == null || patches.isEmpty()) return List.of();
        int concurrency = Math.max(1, Math.min(maxConcurrency, patches.size()));

        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(concurrency);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(patches.size());
        java.util.Set<String> succeeded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<String> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (BookMetadataPatch patch : patches) {
            semaphore.acquire();
            httpClient.newCall(buildMetadataPatchRequest(patch)).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    failed.add(patch.bookId());
                    log.error("❌ 批量 PATCH 失败, BookId: {}, 原因: {}", patch.bookId(), e.getMessage());
                    semaphore.release();
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (response) {
                        if (response.isSuccessful()) {
                            succeeded.add(patch.bookId());
                        } else {
                            failed.add(patch.bookId());
                            log.error("❌ 批量 PATCH 失败, BookId: {}, HTTP {}", patch.bookId(), response.code());
                        }
                    } finally {
                        semaphore.release();
                        latch.countDown();
                    }
                }
            });
        }
        latch.await();
        log.info("📚 批量元数据更新完成: 成功 {}, 失败 {}", succeeded.size(), failed.size());
        return List.copyOf(succeeded);
    }

    private Request buildMetadataPatchRequest(BookMetadataPatch patch) throws Exception {
        String url = baseUrl + "/api/v1/books/" + patch.bookId() + "/metadata";
        String json = objectMapper.writeValueAsString(patch.metadata());
        RequestBody body = RequestBody.create(json, JSON);
        return new Request.Builder().url(url).patch(body).build();
    }
}
