package com.checker.service.impl;

import com.checker.config.EhNetworkConfig;
import com.checker.service.EhTagTranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EhTagTranslation 标签翻译实现：
 * - 启动时从 GitHub 拉取 db.text.json 并构建内存映射（本地离线词典）
 * - 本地词典未命中的标签通过 AI 批量翻译补全（Resilience4j 断路器保护）
 * - 翻译结果写入 Redis 共享缓存（跨实例命中，TTL 24h，Redis 不可用时自动降级）
 * - 每 24 小时自动刷新
 */
@Slf4j
@Service
public class EhTagTranslationServiceImpl implements EhTagTranslationService {

    /**
     * 内置翻译数据库镜像列表（按顺序尝试）。
     * raw.githubusercontent.com 在国内经常不可达，故把 jsDelivr 系列 CDN 作为回退镜像。
     * 可通过 eh-config.tag-db.urls 配置自定义镜像（优先于内置默认）。
     */
    private static final List<String> DEFAULT_DB_URLS = List.of(
            "https://raw.githubusercontent.com/EhTagTranslation/DatabaseReleases/master/db.text.json",
            "https://cdn.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases@master/db.text.json",
            "https://fastly.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases@master/db.text.json",
            "https://gcore.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases@master/db.text.json"
    );

    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24小时

    /** Redis 共享缓存键前缀与 TTL */
    private static final String REDIS_KEY_PREFIX = "eh:tag:tr:";
    private static final Duration REDIS_TTL = Duration.ofHours(24);

    private static final int INITIAL_CAPACITY = 66667;
    private final EhNetworkConfig netConfig;
    private final TaskExecutor backgroundTaskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate;
    private final AiTagTranslationService aiTagTranslationService;

    /** namespace:tag → 中文翻译 */
    private volatile Map<String, String> tagMap = Map.of();

    /** namespace:tag → 描述文本(intro) */
    private volatile Map<String, String> tagDescMap = Map.of();

    /** namespace → 中文名 */
    private volatile Map<String, String> nsMap = Map.of();

    /** AI 翻译结果的内存缓存（namespace:tag 小写 → 译文） */
    private final Map<String, String> aiCache = new ConcurrentHashMap<>();

    /** 正在等待 AI 批量翻译的标签，避免重复提交 */
    private final Set<String> pendingAiTags = ConcurrentHashMap.newKeySet();

    private final AtomicLong lastFetchTime = new AtomicLong(0);
    private final AtomicBoolean refreshScheduled = new AtomicBoolean(false);

    private static final Map<String, String> NS_CHINESE = Map.ofEntries(
            Map.entry("reclass", "重新分类"),
            Map.entry("female", "女性"),
            Map.entry("male", "男性"),
            Map.entry("mixed", "混合"),
            Map.entry("language", "语言"),
            Map.entry("other", "其他"),
            Map.entry("group", "团体"),
            Map.entry("artist", "艺术家"),
            Map.entry("cosplayer", "Cosplayer"),
            Map.entry("parody", "原作"),
            Map.entry("character", "角色"),
            Map.entry("location", "地点"),
            Map.entry("temp", "临时")
    );

    public EhTagTranslationServiceImpl(EhNetworkConfig netConfig,
                                       @Qualifier("backgroundTaskExecutor") TaskExecutor backgroundTaskExecutor,
                                       ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                       ObjectProvider<AiTagTranslationService> aiTagTranslationServiceProvider) {
        this.netConfig = netConfig;
        this.backgroundTaskExecutor = backgroundTaskExecutor;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.aiTagTranslationService = aiTagTranslationServiceProvider.getIfAvailable();
    }

    @PostConstruct
    public void init() {
        // 异步加载，不阻塞启动
        scheduleRefresh("启动时加载 EhTag 翻译数据库失败，标签将保持英文显示");
    }

    @Override
    public String translate(String tag) {
        ensureFresh();
        if (tag == null) return "";

        String key = tag.toLowerCase();

        // 1. 本地离线词典直接命中
        String result = tagMap.get(key);
        if (result != null) return result;

        // 2. AI 翻译内存缓存
        result = aiCache.get(key);
        if (result != null) return result;

        // 3. Redis 共享缓存（跨实例）
        result = redisGet(key);
        if (result != null) {
            aiCache.put(key, result);
            return result;
        }

        // 4. 本地兜底（命名空间翻译/英文透传），并异步提交 AI 批量翻译
        scheduleAiTranslate(List.of(tag));
        return buildFallback(tag);
    }

    @Override
    public Map<String, String> translateBatch(List<String> tags) {
        ensureFresh();
        if (tags == null || tags.isEmpty()) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) continue;
            String key = tag.toLowerCase();
            String translated = tagMap.get(key);
            if (translated == null) translated = aiCache.get(key);
            if (translated == null) translated = redisGet(key);
            if (translated != null) {
                result.put(tag, translated);
            } else {
                misses.add(tag);
            }
        }

        if (!misses.isEmpty()) {
            // 合并为一个 AI Prompt 批量请求；断路器打开/失败时返回空映射并继续本地兜底
            Map<String, String> aiResult = aiTagTranslationService != null
                    ? aiTagTranslationService.batchTranslate(misses)
                    : Map.of();
            aiResult.forEach((original, translated) -> {
                result.put(original, translated);
                aiCache.put(original.toLowerCase(), translated);
                redisSet(original.toLowerCase(), translated);
            });
            for (String miss : misses) {
                if (!result.containsKey(miss)) {
                    result.put(miss, buildFallback(miss));
                }
            }
        }
        return result;
    }

    /**
     * 本地词典兜底：拆分 namespace:tagname，命名空间可翻译则带中文命名空间，
     * 否则直接透传英文原文。
     */
    private String buildFallback(String tag) {
        int colonIdx = tag.indexOf(':');
        if (colonIdx > 0) {
            String ns = tag.substring(0, colonIdx).toLowerCase();
            String name = tag.substring(colonIdx + 1);
            String translatedNs = NS_CHINESE.getOrDefault(ns, ns);
            return translatedNs + ":" + name;
        }
        return tag;
    }

    /**
     * 异步将本地未命中的标签合并提交 AI 批量翻译，结果回写内存与 Redis。
     */
    private void scheduleAiTranslate(List<String> tags) {
        if (aiTagTranslationService == null || tags == null || tags.isEmpty()) return;
        List<String> toSubmit = tags.stream()
                .filter(tag -> pendingAiTags.add(tag.toLowerCase()))
                .toList();
        if (toSubmit.isEmpty()) return;

        try {
            backgroundTaskExecutor.execute(() -> {
                try {
                    Map<String, String> translated = aiTagTranslationService.batchTranslate(toSubmit);
                    translated.forEach((original, value) -> {
                        aiCache.put(original.toLowerCase(), value);
                        redisSet(original.toLowerCase(), value);
                    });
                } catch (Exception e) {
                    log.warn("后台 AI 批量翻译失败: {}", e.getMessage());
                } finally {
                    toSubmit.forEach(tag -> pendingAiTags.remove(tag.toLowerCase()));
                }
            });
        } catch (TaskRejectedException e) {
            toSubmit.forEach(tag -> pendingAiTags.remove(tag.toLowerCase()));
            log.warn("AI 批量翻译任务被拒绝，跳过本次补全");
        }
    }

    private String redisGet(String key) {
        StringRedisTemplate template = this.redisTemplate;
        if (template == null) return null;
        try {
            return template.opsForValue().get(REDIS_KEY_PREFIX + key);
        } catch (Exception e) {
            log.debug("Redis 读取失败（自动降级内存）: {}", e.getMessage());
            return null;
        }
    }

    private void redisSet(String key, String value) {
        StringRedisTemplate template = this.redisTemplate;
        if (template == null) return;
        try {
            template.opsForValue().set(REDIS_KEY_PREFIX + key, value, REDIS_TTL);
        } catch (Exception e) {
            log.debug("Redis 写入失败（自动降级内存）: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, String> getTranslationMap() {
        ensureFresh();
        return Map.copyOf(tagMap);
    }

    @Override
    public String translateNamespace(String namespace) {
        return NS_CHINESE.getOrDefault(namespace.toLowerCase(), namespace);
    }

    @Override
    public void refreshCache() {
        try {
            fetchAndBuild();
        } catch (Exception e) {
            log.error("❌ 手动刷新 EhTag 翻译缓存失败", e);
        }
    }

    @Override
    public String getDescription(String tag) {
        ensureFresh();
        if (tag == null) return "";
        String result = tagDescMap.get(tag.toLowerCase());
        return result != null ? result : "";
    }

    @Override
    public Map<String, String> getDescriptionMap() {
        ensureFresh();
        return Map.copyOf(tagDescMap);
    }

    private void ensureFresh() {
        if (System.currentTimeMillis() - lastFetchTime.get() > CACHE_TTL_MS) {
            scheduleRefresh("后台刷新翻译数据库失败");
        }
    }

    private void scheduleRefresh(String failureMessage) {
        if (!refreshScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            backgroundTaskExecutor.execute(() -> {
                try {
                    fetchAndBuild();
                } catch (Exception e) {
                    log.warn("{}: {}", failureMessage, e.getMessage());
                } finally {
                    refreshScheduled.set(false);
                }
            });
        } catch (TaskRejectedException e) {
            refreshScheduled.set(false);
            log.warn("翻译缓存刷新任务被拒绝: {}", failureMessage);
        }
    }

    private synchronized void fetchAndBuild() throws IOException {
        // 双重检查，避免并发重复拉取
        if (System.currentTimeMillis() - lastFetchTime.get() < 60_000) return;

        log.info("📥 开始拉取 EhTagTranslation 数据库...");

        OkHttpClient client = buildHttpClient();
        IOException lastError = null;
        for (String dbUrl : buildCandidateUrls()) {
            try {
                if (fetchFrom(client, dbUrl)) {
                    return;
                }
            } catch (IOException e) {
                lastError = e;
                log.warn("⚠️ 从 {} 拉取失败: {}，尝试下一个镜像...", dbUrl, e.getMessage());
            }
        }
        throw new IOException("所有翻译数据库镜像均不可用", lastError);
    }

    /**
     * 组装候选下载地址：自定义镜像（eh-config.tag-db.urls）优先，其次内置默认镜像。
     */
    private List<String> buildCandidateUrls() {
        List<String> result = new ArrayList<>();
        List<String> custom = netConfig.getTagDb() != null ? netConfig.getTagDb().getUrls() : null;
        if (custom != null && !custom.isEmpty()) {
            custom.stream().filter(url -> url != null && !url.isBlank()).forEach(result::add);
        }
        result.addAll(DEFAULT_DB_URLS);
        return result;
    }

    /**
     * 从指定 URL 拉取并解析翻译数据库。
     *
     * @return true 表示解析成功并已更新内存映射；false 表示内容为空（结构可能已变更）
     */
    private boolean fetchFrom(OkHttpClient client, String dbUrl) throws IOException {
        Request request = new Request.Builder()
                .url(dbUrl)
                .header("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }

            JsonNode root = objectMapper.readTree(response.body().byteStream());
            Map<String, String> tempTagMap = new HashMap<>(INITIAL_CAPACITY);
            Map<String, String> tempDescMap = new HashMap<>(INITIAL_CAPACITY);

            JsonNode dataArray = root.get("data");
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode nsNode : dataArray) {
                    String namespace = nsNode.path("namespace").asText("");
                    JsonNode data = nsNode.get("data");
                    if (data != null && data.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            String tagKey = entry.getKey().toLowerCase();
                            String fullKey = namespace + ":" + tagKey;
                            String chineseName = entry.getValue().path("name").asText("");
                            String intro = entry.getValue().path("intro").asText("");
                            if (!chineseName.isEmpty()) {
                                tempTagMap.put(fullKey, chineseName);
                            }
                            if (!intro.isEmpty()) {
                                tempDescMap.put(fullKey, intro);
                            }
                        }
                    }
                }
            }

            if (tempTagMap.isEmpty()) {
                log.warn("⚠️ 翻译数据库解析结果为空（可能文件结构已变更），来源: {}", dbUrl);
                return false;
            }

            tagMap = Map.copyOf(tempTagMap);
            tagDescMap = Map.copyOf(tempDescMap);
            nsMap = Map.copyOf(NS_CHINESE);
            lastFetchTime.set(System.currentTimeMillis());
            log.info("✅ EhTag 翻译数据库加载成功（来源 {}），共 {} 条翻译记录，{} 条描述记录",
                    dbUrl, tagMap.size(), tagDescMap.size());
            return true;
        }
    }

    private OkHttpClient buildHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS);

        // 使用项目已配置的代理
        EhNetworkConfig.Proxy proxyConfig = netConfig.getProxy();
        if (proxyConfig != null && proxyConfig.getHost() != null && !proxyConfig.getHost().isEmpty()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()));
            builder.proxy(proxy);

            if (proxyConfig.getUsername() != null && !proxyConfig.getUsername().isEmpty()) {
                builder.proxyAuthenticator((route, resp) -> {
                    String credential = Credentials.basic(proxyConfig.getUsername(), proxyConfig.getPassword());
                    return resp.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
            }
        }

        return builder.build();
    }
}
