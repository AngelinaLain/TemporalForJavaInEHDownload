package com.checker.service.impl;

import com.checker.config.EhNetworkConfig;
import com.checker.service.EhTagTranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EhTagTranslation 标签翻译实现：
 * - 启动时从 GitHub 拉取 db.text.json 并构建内存映射
 * - 使用 ConcurrentHashMap 作为高性能缓存
 * - 每 24 小时自动刷新
 */
@Slf4j
@Service
public class EhTagTranslationServiceImpl implements EhTagTranslationService {

    private static final String DB_URL =
            "https://raw.githubusercontent.com/EhTagTranslation/DatabaseReleases/master/db.text.json";

    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24小时

    private static final int INITIAL_CAPACITY = 66667;
    private final EhNetworkConfig netConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** namespace:tag → 中文翻译 */
    private volatile Map<String, String> tagMap = Map.of();

    /** namespace:tag → 描述文本(intro) */
    private volatile Map<String, String> tagDescMap = Map.of();

    /** namespace → 中文名 */
    private volatile Map<String, String> nsMap = Map.of();

    private final AtomicLong lastFetchTime = new AtomicLong(0);

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

    public EhTagTranslationServiceImpl(EhNetworkConfig netConfig) {
        this.netConfig = netConfig;
    }

    @PostConstruct
    public void init() {
        // 异步加载，不阻塞启动
        CompletableFuture.runAsync(() -> {
            try {
                fetchAndBuild();
            } catch (Exception e) {
                log.warn("⚠️ 启动时加载 EhTag 翻译数据库失败，标签将保持英文显示: {}", e.getMessage());
            }
        });
    }

    @Override
    public String translate(String tag) {
        ensureFresh();
        if (tag == null) return "";

        // 直接完整匹配
        String result = tagMap.get(tag.toLowerCase());
        if (result != null) return result;

        // 尝试拆分 namespace:tagname
        int colonIdx = tag.indexOf(':');
        if (colonIdx > 0) {
            String ns = tag.substring(0, colonIdx).toLowerCase();
            String name = tag.substring(colonIdx + 1).toLowerCase();
            String translatedName = tagMap.get(ns + ":" + name);
            String translatedNs = NS_CHINESE.getOrDefault(ns, ns);
            if (translatedName != null) {
                return translatedNs + ":" + translatedName;
            }
            return translatedNs + ":" + name;
        }

        return tag;
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
            CompletableFuture.runAsync(() -> {
                try {
                    fetchAndBuild();
                } catch (Exception e) {
                    log.warn("⚠️ 后台刷新翻译数据库失败: {}", e.getMessage());
                }
            });
        }
    }

    private synchronized void fetchAndBuild() throws IOException {
        // 双重检查，避免并发重复拉取
        if (System.currentTimeMillis() - lastFetchTime.get() < 60_000) return;

        log.info("📥 开始从 GitHub 拉取 EhTagTranslation 数据库...");

        OkHttpClient client = buildHttpClient();
        Request request = new Request.Builder()
                .url(DB_URL)
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

            tagMap = Map.copyOf(tempTagMap);
            tagDescMap = Map.copyOf(tempDescMap);
            nsMap = Map.copyOf(NS_CHINESE);
            lastFetchTime.set(System.currentTimeMillis());
            log.info("✅ EhTag 翻译数据库加载成功，共 {} 条翻译记录，{} 条描述记录", tagMap.size(), tagDescMap.size());
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
