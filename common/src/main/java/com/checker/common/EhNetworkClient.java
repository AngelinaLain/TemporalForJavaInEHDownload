package com.checker.common;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.temporal.failure.ApplicationFailure;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EHentai 网络客户端：使用 OkHttp 重构，完美解决 HTTPS 代理隧道 (CONNECT) 407 问题，
 * 且代理认证仅对当前 OkHttpClient 实例生效，不污染 JVM 全局环境。
 * <p>
 * 增强能力：
 * <ul>
 *   <li><b>智能限流</b>：Resilience4j 令牌桶对 e-hentai API 做全局请求速率控制，防封禁；</li>
 *   <li><b>动态代理池</b>：支持多代理轮换，遭遇 403/502 自动冷却当前代理并切换；</li>
 *   <li><b>Cookie 账号轮转</b>：多账号 Cookie 池轮询分散单账号频率，Cookie 失效自动切换；</li>
 *   <li><b>断点续传</b>：{@link #downloadWithResume(String, Path)} 支持 HTTP Range 分块续传。</li>
 * </ul>
 */
@Slf4j
@Component
public class EhNetworkClient {

    @Autowired
    private EhNetworkConfig netConfig;

    /** 每个代理对应一个 OkHttpClient，未配置代理时退化为单一默认客户端 */
    private final List<ClientEntry> clientEntries = new ArrayList<>();
    private final AtomicInteger proxyRoundRobin = new AtomicInteger(0);
    private final AtomicInteger cookieRoundRobin = new AtomicInteger(0);

    /** 全局令牌桶限流器，null 表示未启用 */
    private volatile RateLimiter rateLimiter;

    /** 参与轮转的 Cookie 账号集合 */
    private volatile List<EhNetworkConfig.Cookies> cookieAccounts = List.of();

    private static final int TIMEOUT_SECONDS = 15;
    private static final int MAX_FAILOVER_ATTEMPTS = 6;
    private static final int RESUME_CHUNK_BYTES = 4 * 1024 * 1024;

    @PostConstruct
    public void init() {
        // 1. 构建代理客户端池
        List<EhNetworkConfig.Proxy> proxies = buildProxyPool();
        for (EhNetworkConfig.Proxy proxyConfig : proxies) {
            clientEntries.add(new ClientEntry(buildClient(proxyConfig), proxyConfig));
        }
        if (clientEntries.isEmpty()) {
            clientEntries.add(new ClientEntry(buildClient(null), null));
            log.info("未配置任何代理，使用默认直连客户端");
        } else {
            log.info("✅ 代理池初始化完成，共 {} 个代理", clientEntries.size());
        }

        // 2. 构建 Cookie 账号轮转池
        List<EhNetworkConfig.Cookies> accounts = new ArrayList<>();
        List<EhNetworkConfig.Cookies> pool = netConfig.getCookiePool();
        if (pool != null && !pool.isEmpty()) {
            pool.stream().filter(EhNetworkConfig.Cookies::isConfigured).forEach(accounts::add);
        }
        if (accounts.isEmpty() && netConfig.getCookies().isConfigured()) {
            accounts.add(netConfig.getCookies());
        }
        this.cookieAccounts = List.copyOf(accounts);
        if (cookieAccounts.isEmpty()) {
            log.warn("⚠️ 未配置 Cookie 账号，请求将不带 Cookie（可能无法抓取受限内容）");
        } else {
            log.info("✅ Cookie 账号池初始化完成，共 {} 个账号", cookieAccounts.size());
        }

        // 3. 初始化全局限流器（令牌桶）
        EhNetworkConfig.RateLimit rateConfig = netConfig.getRateLimit();
        if (rateConfig != null && rateConfig.isEnabled()) {
            RateLimiterConfig config = RateLimiterConfig.custom()
                    .limitForPeriod(rateConfig.getLimitForPeriod())
                    .limitRefreshPeriod(Duration.ofSeconds(rateConfig.getLimitRefreshPeriodSeconds()))
                    .timeoutDuration(Duration.ofSeconds(rateConfig.getTimeoutDurationSeconds()))
                    .build();
            this.rateLimiter = RateLimiter.of("eh-global", config);
            log.info("✅ EHentai 全局限流已启用: {} 请求 / {} 秒", rateConfig.getLimitForPeriod(),
                    rateConfig.getLimitRefreshPeriodSeconds());
        } else {
            log.warn("⚠️ EHentai 全局限流未启用，存在被封禁风险");
        }
    }

    private List<EhNetworkConfig.Proxy> buildProxyPool() {
        List<EhNetworkConfig.Proxy> result = new ArrayList<>();
        List<EhNetworkConfig.Proxy> pool = netConfig.getProxyPool();
        if (pool != null && !pool.isEmpty()) {
            pool.stream().filter(EhNetworkConfig.Proxy::isConfigured).forEach(result::add);
        }
        if (result.isEmpty() && netConfig.getProxy() != null && netConfig.getProxy().isConfigured()) {
            result.add(netConfig.getProxy());
        }
        return result;
    }

    private OkHttpClient buildClient(EhNetworkConfig.Proxy proxyConfig) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (proxyConfig != null) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()));
            builder.proxy(proxy);

            if (StrUtil.isNotBlank(proxyConfig.getUsername()) && StrUtil.isNotBlank(proxyConfig.getPassword())) {
                builder.proxyAuthenticator((route, response) -> {
                    String credential = Credentials.basic(proxyConfig.getUsername(), proxyConfig.getPassword());
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
                log.info("✅ OkHttp 代理认证已配置，安全隔离不污染全局: {}:{}", proxyConfig.getHost(), proxyConfig.getPort());
            }
        }
        return builder.build();
    }

    public String getHtml(String url) {
        log.info("正在请求 EHentai 页面: {}", url);
        return executeWithFailover(url, requestBuilder -> requestBuilder.get().build());
    }

    public String postForm(String url, Map<String, Object> formParams) {
        log.info("正在提交 EHentai 表单: {}", url);
        FormBody.Builder formBuilder = new FormBody.Builder();
        if (formParams != null) {
            formParams.forEach((k, v) -> formBuilder.add(k, String.valueOf(v)));
        }
        return executeWithFailover(url, requestBuilder -> requestBuilder.post(formBuilder.build()).build());
    }

    public String postJson(String url, String jsonBody) {
        log.info("正在提交 JSON 请求: {}", url);
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        return executeWithFailover(url, requestBuilder -> requestBuilder.post(body).build());
    }

    /**
     * 带代理/账号故障转移的请求执行：
     * <ol>
     *   <li>轮询选择一个可用代理客户端（冷却期内的代理被跳过）；</li>
     *   <li>轮询选择一个 Cookie 账号；</li>
     *   <li>从全局令牌桶获取许可（超时抛出 NETWORK_ERROR）；</li>
     *   <li>403/502 → 冷却当前代理并切换重试；Cookie 失效 → 切换账号重试；</li>
     *   <li>509 配额超限等致命错误直接上抛。</li>
     * </ol>
     */
    private String executeWithFailover(String url, RequestFactory requestFactory) {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < MAX_FAILOVER_ATTEMPTS; attempt++) {
            ClientEntry entry = pickAvailableClient();
            String cookie = pickCookie();

            Request.Builder builder = buildBaseRequest(url);
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            Request request = requestFactory.build(builder);

            acquireRateLimitPermit(url);

            try (Response response = entry.client.newCall(request).execute()) {
                int status = response.code();
                if (status == 403 || status == 502) {
                    long cooldownMs = TimeUnit.SECONDS.toMillis(
                            netConfig.getRateLimit() != null ? netConfig.getRateLimit().getProxyCooldownSeconds() : 300);
                    entry.coolDownUntil = System.currentTimeMillis() + cooldownMs;
                    log.warn("⛔ 代理遭遇 403/502，已冷却 {} 秒并切换下一个代理 (第 {} 次尝试) - URL: {}",
                            cooldownMs / 1000, attempt + 1, url);
                    lastFailure = ApplicationFailure.newFailure("IP 被封禁或节点不可用", ErrorType.IP_BANNED.getCode());
                    continue;
                }
                return handleResponse(url, response);
            } catch (ApplicationFailure failure) {
                if (ErrorType.COOKIE_EXPIRED.getCode().equals(failure.getType())) {
                    log.warn("🔄 Cookie 已失效，切换到下一个账号重试 (第 {} 次尝试) - URL: {}", attempt + 1, url);
                    cookieRoundRobin.incrementAndGet();
                    lastFailure = failure;
                    continue;
                }
                throw failure;
            } catch (Exception e) {
                log.warn("网络请求异常 (第 {} 次尝试): {} - URL: {}", attempt + 1, e.getMessage(), url);
                lastFailure = e;
            }
        }
        if (lastFailure instanceof ApplicationFailure failure) {
            throw failure;
        }
        log.error("网络请求发生未知致命异常: {}", lastFailure != null ? lastFailure.getMessage() : "unknown");
        throw ApplicationFailure.newFailure("代理失效或网络无法连接: " + (lastFailure != null ? lastFailure.getMessage() : "unknown"),
                ErrorType.NETWORK_ERROR.getCode());
    }

    private void acquireRateLimitPermit(String url) {
        RateLimiter limiter = this.rateLimiter;
        if (limiter == null) return;
        boolean permitted = limiter.acquirePermission();
        if (!permitted) {
            log.error("⏱️ 全局限流等待超时，请求被拒绝 - URL: {}", url);
            throw ApplicationFailure.newFailure("EHentai 全局限流等待超时", ErrorType.NETWORK_ERROR.getCode());
        }
    }

    private ClientEntry pickAvailableClient() {
        int size = clientEntries.size();
        if (size == 1) return clientEntries.get(0);

        long now = System.currentTimeMillis();
        int start = Math.floorMod(proxyRoundRobin.getAndIncrement(), size);
        ClientEntry fallback = clientEntries.get(0);
        for (int i = 0; i < size; i++) {
            ClientEntry entry = clientEntries.get((start + i) % size);
            if (entry.coolDownUntil <= now) {
                return entry;
            }
            fallback = entry;
        }
        log.warn("所有代理均处于冷却期，选用冷却剩余时间最短的代理");
        return fallback;
    }

    private String pickCookie() {
        List<EhNetworkConfig.Cookies> accounts = this.cookieAccounts;
        if (accounts.isEmpty()) return null;
        int index = Math.floorMod(cookieRoundRobin.getAndIncrement(), accounts.size());
        return accounts.get(index).getFullCookieString();
    }

    private String handleResponse(String url, Response response) throws IOException {
        int status = response.code();
        if (status == 509) {
            log.error("配额超限 (509 Bandwidth Exceeded) - URL: {}", url);
            throw ApplicationFailure.newFailure("触发 509 配额超限", ErrorType.QUOTA_EXCEEDED.getCode());
        }
        if (!response.isSuccessful()) {
            log.error("请求失败，HTTP 状态码: {} - URL: {}", status, url);
            throw new RuntimeException("请求异常: HTTP " + status);
        }
        ResponseBody body = response.body();
        String bodyStr = body != null ? body.string() : "";
        // Cookie 失效检测：响应内容含有登录表单特征，说明被重定向到登录页
        if (bodyStr.contains("ipb_login_form") || bodyStr.contains("Please login to continue")) {
            log.error("❌ Cookie 已失效，被重定向至登录页 - URL: {}", url);
            throw ApplicationFailure.newNonRetryableFailure(
                    "Cookie 已失效，需要人工更新", ErrorType.COOKIE_EXPIRED.getCode());
        }
        return bodyStr;
    }

    /**
     * 带断点续传的分块下载：
     * <ol>
     *   <li>HEAD 探测 Content-Length；</li>
     *   <li>本地已有 .part 文件则从已下载偏移处继续；</li>
     *   <li>以 {@link #RESUME_CHUNK_BYTES} 为块大小通过 HTTP Range 逐块下载；</li>
     *   <li>全部完成后将 .part 原子重命名为目标文件。</li>
     * </ol>
     *
     * @param url    下载直链
     * @param target 目标文件路径（磁盘上会短暂存在 target.part 临时文件）
     * @return 最终下载完成的文件大小（字节）
     */
    public long downloadWithResume(String url, Path target) throws IOException {
        Path partFile = target.resolveSibling(target.getFileName() + ".part");
        long totalBytes = Files.exists(partFile) ? Files.size(partFile) : 0;
        if (totalBytes > 0) {
            log.info("🔄 检测到未完成下载片段 {} 字节，将从偏移处续传: {}", totalBytes, url);
        }

        Long contentLength = fetchContentLength(url);
        if (contentLength == null || contentLength <= 0) {
            log.warn("⚠️ 服务器不支持 HEAD/Content-Length，退化为整段下载");
            return downloadPlain(url, target, partFile, totalBytes);
        }

        while (totalBytes < contentLength) {
            long chunkStart = totalBytes;
            long chunkEnd = Math.min(chunkStart + RESUME_CHUNK_BYTES - 1, contentLength - 1);
            byte[] chunk = downloadRange(url, "bytes=" + chunkStart + "-" + chunkEnd);
            try (OutputStream out = Files.newOutputStream(partFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write(chunk);
            }
            totalBytes += chunk.length;
            log.info("⏬ 下载进度: {}/{} 字节 ({:.1f}%) - {}",
                    totalBytes, contentLength, totalBytes * 100.0 / contentLength, target.getFileName());
        }

        Files.move(partFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.info("✅ 分块下载完成，共 {} 字节: {}", totalBytes, target);
        return totalBytes;
    }

    /** 整段下载兜底（服务器不支持 Range 时使用），同样支持 .part 追加。 */
    private long downloadPlain(String url, Path target, Path partFile, long totalBytes) throws IOException {
        while (true) {
            ClientEntry entry = pickAvailableClient();
            Request.Builder builder = new Request.Builder().url(url).get();
            String cookie = pickCookie();
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            acquireRateLimitPermit(url);

            try (Response response = entry.client.newCall(builder.build()).execute()) {
                int status = response.code();
                if (status == 403 || status == 502) {
                    coolDown(entry);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("下载失败，HTTP " + status);
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("下载响应为空");
                }
                try (InputStream in = body.byteStream();
                     OutputStream out = Files.newOutputStream(partFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    byte[] buffer = new byte[RESUME_CHUNK_BYTES];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        totalBytes += read;
                    }
                }
                Files.move(partFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("✅ 整段下载完成，共 {} 字节: {}", totalBytes, target);
                return totalBytes;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("整段下载失败: " + e.getMessage(), e);
            }
        }
    }

    private Long fetchContentLength(String url) {
        for (int attempt = 0; attempt < MAX_FAILOVER_ATTEMPTS; attempt++) {
            ClientEntry entry = pickAvailableClient();
            Request.Builder builder = new Request.Builder().url(url).head();
            String cookie = pickCookie();
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            try (Response response = entry.client.newCall(builder.build()).execute()) {
                int status = response.code();
                if (status == 403 || status == 502) {
                    coolDown(entry);
                    continue;
                }
                if (status == 200 || status == 206) {
                    String header = response.header("Content-Length");
                    return header != null ? Long.parseLong(header) : null;
                }
                return null;
            } catch (Exception e) {
                log.warn("HEAD 探测失败 (第 {} 次尝试): {}", attempt + 1, e.getMessage());
            }
        }
        return null;
    }

    private byte[] downloadRange(String url, String range) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < MAX_FAILOVER_ATTEMPTS; attempt++) {
            ClientEntry entry = pickAvailableClient();
            Request.Builder builder = new Request.Builder().url(url).header("Range", range).get();
            String cookie = pickCookie();
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            acquireRateLimitPermit(url);

            try (Response response = entry.client.newCall(builder.build()).execute()) {
                int status = response.code();
                if (status == 403 || status == 502) {
                    coolDown(entry);
                    continue;
                }
                if (status == 200 || status == 206) {
                    ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("分块响应为空");
                    }
                    return body.bytes();
                }
                throw new IOException("分块下载失败，HTTP " + status);
            } catch (IOException e) {
                lastFailure = e;
                log.warn("分块下载异常 (第 {} 次尝试): {}", attempt + 1, e.getMessage());
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("分块下载失败: " + range);
    }

    private void coolDown(ClientEntry entry) {
        long cooldownMs = TimeUnit.SECONDS.toMillis(
                netConfig.getRateLimit() != null ? netConfig.getRateLimit().getProxyCooldownSeconds() : 300);
        entry.coolDownUntil = System.currentTimeMillis() + cooldownMs;
    }

    private Request.Builder buildBaseRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7");
    }

    @FunctionalInterface
    private interface RequestFactory {
        Request build(Request.Builder builder);
    }

    /** 客户端条目：代理配置 + 冷却截止时间戳 */
    private static final class ClientEntry {
        final OkHttpClient client;
        @SuppressWarnings("unused")
        final EhNetworkConfig.Proxy proxy;
        volatile long coolDownUntil = 0;

        ClientEntry(OkHttpClient client, EhNetworkConfig.Proxy proxy) {
            this.client = client;
            this.proxy = proxy;
        }
    }
}
