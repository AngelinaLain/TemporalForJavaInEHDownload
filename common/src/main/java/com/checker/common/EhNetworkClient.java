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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * 封装源站请求和大文件下载，统一处理 Cookie、代理切换、限流及网络重试。
 * 下载使用磁盘分片和校验信息续传，普通网页请求与长时间文件下载分别控制超时。
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
    private static final int DOWNLOAD_BUFFER_BYTES = 128 * 1024;
    private static final long PROGRESS_CALLBACK_INTERVAL_MS = 1_000;

    /**
     * 读取代理与账号池并创建客户端，配置全局限流器，供之后的请求共享。
     */
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

    /**
     * 整理可用代理配置，优先使用多代理池，没有时兼容单代理设置。
     */
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

    /**
     * 按代理地址和认证信息创建普通请求客户端，并设置连接与读取超时。
     */
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

    /**
     * 通过统一限流和故障切换逻辑读取网页内容。
     */
    public String getHtml(String url) {
        log.info("正在请求 EHentai 页面: {}", url);
        return executeWithFailover(url, requestBuilder -> requestBuilder.get().build());
    }

    /**
     * 按大小上限读取二进制响应，超出限制或源站返回错误时终止，避免无限占用内存。
     */
    public byte[] getBytes(String url, long maxBytes) {
        if (maxBytes <= 0 || maxBytes > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("二进制响应大小上限必须在 1-100MB 之间");
        }
        Exception lastFailure = null;
        for (int attempt = 0; attempt < MAX_FAILOVER_ATTEMPTS; attempt++) {
            ClientEntry entry = pickAvailableClient();
            Request.Builder builder = buildBaseRequest(url);
            String cookie = pickCookie();
            if (cookie != null) builder.header("Cookie", cookie);
            acquireRateLimitPermit(url);
            try (Response response = entry.client.newCall(builder.get().build()).execute()) {
                int status = response.code();
                if (status == 403 || status == 502) {
                    long cooldownMs = TimeUnit.SECONDS.toMillis(
                            netConfig.getRateLimit() != null ? netConfig.getRateLimit().getProxyCooldownSeconds() : 300);
                    entry.coolDownUntil = System.currentTimeMillis() + cooldownMs;
                    lastFailure = new IOException("图片节点返回 HTTP " + status);
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("图片请求失败: HTTP " + status);
                }
                long declared = response.body().contentLength();
                if (declared > maxBytes) throw new IOException("图片响应超过大小限制: " + declared);
                try (java.io.InputStream input = response.body().byteStream();
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) throw new IOException("图片响应超过大小限制: " + total);
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                }
            } catch (Exception e) {
                lastFailure = e;
                log.warn("二进制资源请求失败 (第 {} 次): {} - URL: {}", attempt + 1, e.getMessage(), url);
            }
        }
        throw ApplicationFailure.newFailure("图片资源下载失败: "
                        + (lastFailure == null ? "unknown" : lastFailure.getMessage()),
                ErrorType.NETWORK_ERROR.getCode());
    }

    /**
     * 发送表单请求，并复用账号、代理与错误处理规则。
     */
    public String postForm(String url, Map<String, Object> formParams) {
        log.info("正在提交 EHentai 表单: {}", url);
        FormBody.Builder formBuilder = new FormBody.Builder();
        if (formParams != null) {
            formParams.forEach((k, v) -> formBuilder.add(k, String.valueOf(v)));
        }
        return executeWithFailover(url, requestBuilder -> requestBuilder.post(formBuilder.build()).build());
    }

    /**
     * 发送 JSON 请求，供源站元数据等接口使用。
     */
    public String postJson(String url, String jsonBody) {
        log.info("正在提交 JSON 请求: {}", url);
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        return executeWithFailover(url, requestBuilder -> requestBuilder.post(body).build());
    }

    /**
     * 轮流选择可用代理和账号；节点被阻止时冷却，账号失效时切换，配额等明确错误直接交给工作流。
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

    /**
     * 等待源站全局请求额度，超时后停止本次请求，避免突破配置的访问频率。
     */
    private void acquireRateLimitPermit(String url) {
        RateLimiter limiter = this.rateLimiter;
        if (limiter == null) return;
        boolean permitted = limiter.acquirePermission();
        if (!permitted) {
            log.error("⏱️ 全局限流等待超时，请求被拒绝 - URL: {}", url);
            throw ApplicationFailure.newFailure("EHentai 全局限流等待超时", ErrorType.NETWORK_ERROR.getCode());
        }
    }

    /**
     * 从代理池轮换选择当前可用的客户端，全部处于冷却期时使用现有备用选择逻辑。
     */
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

    /**
     * 从有效账号池轮流取一个 Cookie，把请求分散到多个已配置账号。
     */
    private String pickCookie() {
        List<EhNetworkConfig.Cookies> accounts = this.cookieAccounts;
        if (accounts.isEmpty()) return null;
        int index = Math.floorMod(cookieRoundRobin.getAndIncrement(), accounts.size());
        return accounts.get(index).getFullCookieString();
    }

    /**
     * 检查响应状态及登录页特征，把配额不足、凭据失效和普通网络故障区分开。
     */
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
     * 把下载内容流式写入磁盘分片，依据已保存的响应校验信息请求剩余字节，并报告进度。
     * 源站不支持断点、响应范围不一致或文件已变化时重新开始，避免把不同内容拼成一个归档。
     * 只有实际字节数符合预期才发布为完成文件；普通网络故障保留断点重试，真正取消立即退出。
     */
    public long downloadWithResume(String url, Path target) throws IOException {
        return downloadWithResume(url, target, bytes -> {
        });
    }

    /**
     * 把下载内容流式写入磁盘分片，依据已保存的响应校验信息请求剩余字节，并报告进度。
     * 源站不支持断点、响应范围不一致或文件已变化时重新开始，避免把不同内容拼成一个归档。
     * 只有实际字节数符合预期才发布为完成文件；普通网络故障保留断点重试，真正取消立即退出。
     */
    public long downloadWithResume(String url, Path target, LongConsumer progress) throws IOException {
        Path partFile = target.resolveSibling(target.getFileName() + ".part");
        Path metadataFile = partFile.resolveSibling(partFile.getFileName() + ".meta");
        ResumeMetadata metadata = loadResumeMetadata(metadataFile);
        IOException lastIoFailure = null;
        boolean sawBlockedProxy = false;
        int maxAttempts = Math.max(1, netConfig.getDownload().getMaxAttempts());

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            long offset = Files.exists(partFile) ? Files.size(partFile) : 0;
            if (attempt == 0 && offset > 0) {
                log.info("🔄 检测到未完成下载片段 {} 字节，将从偏移处续传: {}", offset, url);
                progress.accept(offset);
            }

            ClientEntry entry = pickAvailableClient();
            Request.Builder builder = buildBaseRequest(url)
                    .header("Range", "bytes=" + offset + "-")
                    .get();
            if (offset > 0 && metadata.validator() != null) {
                builder.header("If-Range", metadata.validator());
            }
            String cookie = pickCookie();
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }

            acquireRateLimitPermit(url);
            try (Response response = downloadClient(entry.client).newCall(builder.build()).execute()) {
                int status = response.code();
                if (status == 403 || status == 502) {
                    sawBlockedProxy = true;
                    coolDown(entry);
                    lastIoFailure = new IOException("下载节点返回 HTTP " + status);
                    continue;
                }
                if (status == 509) {
                    throw ApplicationFailure.newNonRetryableFailure(
                            "触发 509 配额超限", ErrorType.QUOTA_EXCEEDED.getCode());
                }
                if (status == 429 || status == 503) {
                    long waitMs = backoffWaitMs(response, attempt);
                    log.warn("⏳ HTTP {} 下载限流，等待 {} 毫秒后重试 (第 {} 次尝试)",
                            status, waitMs, attempt + 1);
                    sleepInterruptibly(waitMs);
                    continue;
                }
                if (status == 416) {
                    long remoteTotal = parseUnsatisfiedRangeTotal(response.header("Content-Range"));
                    if (offset > 0 && remoteTotal == offset) {
                        return publishCompletedDownload(partFile, metadataFile, target, offset);
                    }
                    log.warn("Range 416 与本地缓存不匹配（本地 {}, 远端 {}），清空后重新下载", offset, remoteTotal);
                    resetPartialDownload(partFile, metadataFile);
                    metadata = ResumeMetadata.EMPTY;
                    lastIoFailure = new IOException("Range 416 与本地缓存不匹配");
                    continue;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("下载响应为空");
                }
                if (isHtmlResponse(response)) {
                    String html = body.string();
                    if (html.contains("ipb_login_form") || html.contains("Please login to continue")) {
                        throw ApplicationFailure.newNonRetryableFailure(
                                "Cookie 已失效，需要人工更新", ErrorType.COOKIE_EXPIRED.getCode());
                    }
                    if (html.contains("509 Bandwidth Limit Exceeded") || html.contains("You have exceeded your image viewing limits")) {
                        throw ApplicationFailure.newNonRetryableFailure(
                                "触发 EHentai 下载配额限制", ErrorType.QUOTA_EXCEEDED.getCode());
                    }
                    // 临时归档直链通常在过期后返回一个 HTML 提示页。继续请求同一个 URL
                    // 不会恢复，立即交回 Workflow.retry 重新调用 extractDownloadUrl。
                    throw ApplicationFailure.newFailure(
                            "下载直链已失效，需要重新获取", ErrorType.DOWNLOAD_URL_EXPIRED.getCode());
                }

                long expectedTotal;
                boolean append;
                if (status == 206) {
                    ContentRange range = parseContentRange(response.header("Content-Range"));
                    if (range == null || range.start() != offset || range.end() < range.start()) {
                        throw new IOException("无效的 Content-Range: " + response.header("Content-Range")
                                + "，期望起点 " + offset);
                    }
                    if (offset > 0 && metadata.total() > 0 && metadata.total() != range.total()) {
                        log.warn("远端文件总长度已变化（{} -> {}），清空旧缓存后重新下载",
                                metadata.total(), range.total());
                        resetPartialDownload(partFile, metadataFile);
                        metadata = ResumeMetadata.EMPTY;
                        lastIoFailure = new IOException("远端文件总长度在续传期间发生变化");
                        continue;
                    }
                    String responseValidator = responseValidator(response);
                    if (offset > 0 && metadata.validator() != null && responseValidator != null
                            && !metadata.validator().equals(responseValidator)) {
                        log.warn("远端文件校验标识已变化，清空旧缓存后重新下载");
                        resetPartialDownload(partFile, metadataFile);
                        metadata = ResumeMetadata.EMPTY;
                        lastIoFailure = new IOException("远端文件在续传期间发生变化");
                        continue;
                    }
                    expectedTotal = range.total();
                    append = offset > 0;
                } else if (status == 200) {
                    // If-Range 不匹配或服务端不支持 Range 时会返回完整内容，必须覆盖旧缓存。
                    expectedTotal = body.contentLength();
                    append = false;
                    offset = 0;
                } else {
                    throw new IOException("下载失败，HTTP " + status);
                }

                metadata = new ResumeMetadata(response.header("ETag"), response.header("Last-Modified"), expectedTotal);
                saveResumeMetadata(metadataFile, metadata);
                long downloaded = streamResponseToFile(body, partFile, append, offset, expectedTotal, progress);
                if (expectedTotal > 0 && downloaded != expectedTotal) {
                    lastIoFailure = new IOException("下载长度不完整: " + downloaded + "/" + expectedTotal);
                    log.warn("{}，将从当前偏移继续", lastIoFailure.getMessage());
                    continue;
                }
                return publishCompletedDownload(partFile, metadataFile, target, downloaded);
            } catch (SocketTimeoutException e) {
                // SocketTimeoutException 继承 InterruptedIOException。旧逻辑把普通 TLS/读取超时
                // 误当作 Activity 取消并立即抛出，导致配置的多次断点重试从未执行。
                lastIoFailure = e;
                coolDown(entry);
                log.warn("下载连接/读取超时 (第 {}/{} 次)，保留断点后重试: {}",
                        attempt + 1, maxAttempts, e.getMessage());
                waitBeforeDownloadRetry(attempt, maxAttempts);
            } catch (InterruptedIOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    // 真正的线程中断/取消必须立即传播，让 Temporal 尽快停止 Activity。
                    throw e;
                }
                lastIoFailure = e;
                log.warn("下载 I/O 被中断 (第 {}/{} 次)，保留断点后重试: {}",
                        attempt + 1, maxAttempts, e.getMessage());
                waitBeforeDownloadRetry(attempt, maxAttempts);
            } catch (IOException e) {
                lastIoFailure = e;
                log.warn("流式下载异常 (第 {}/{} 次): {}", attempt + 1, maxAttempts, e.getMessage());
                waitBeforeDownloadRetry(attempt, maxAttempts);
            }
        }

        if (sawBlockedProxy && lastIoFailure != null) {
            throw ApplicationFailure.newFailure(
                    "IP 被封禁或下载节点不可用: " + lastIoFailure.getMessage(), ErrorType.IP_BANNED.getCode());
        }
        throw lastIoFailure != null ? lastIoFailure : new IOException("下载失败，已耗尽重试次数");
    }

    /**
     * 为长文件下载单独设置超时，避免普通网页请求的限制过早打断下载。
     */
    private OkHttpClient downloadClient(OkHttpClient baseClient) {
        EhNetworkConfig.Download config = netConfig.getDownload();
        return baseClient.newBuilder()
                .connectTimeout(Math.max(1, config.getConnectTimeoutSeconds()), TimeUnit.SECONDS)
                .readTimeout(Math.max(1, config.getReadTimeoutSeconds()), TimeUnit.SECONDS)
                .writeTimeout(Math.max(1, config.getWriteTimeoutSeconds()), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 在仍有重试机会时按配置退避等待，并保持线程取消可以及时生效。
     */
    private void waitBeforeDownloadRetry(int attempt, int maxAttempts) throws InterruptedIOException {
        if (attempt + 1 >= maxAttempts) return;
        EhNetworkConfig.Download config = netConfig.getDownload();
        long initialMs = TimeUnit.SECONDS.toMillis(Math.max(1, config.getInitialBackoffSeconds()));
        long maxMs = TimeUnit.SECONDS.toMillis(Math.max(1, config.getMaxBackoffSeconds()));
        long multiplier = 1L << Math.min(attempt, 10);
        long waitMs = initialMs >= maxMs / multiplier ? maxMs : initialMs * multiplier;
        log.info("⏳ 下载网络恢复等待 {} 秒，之后从断点继续", TimeUnit.MILLISECONDS.toSeconds(waitMs));
        sleepInterruptibly(waitMs);
    }

    /**
     * 把响应分块写入分片文件，按续传偏移累计已下载字节并定期回报进度。
     */
    private long streamResponseToFile(ResponseBody body, Path partFile, boolean append, long initialOffset,
                                      long expectedTotal, LongConsumer progress) throws IOException {
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
        long downloaded = initialOffset;
        long lastProgressAt = 0;
        byte[] buffer = new byte[DOWNLOAD_BUFFER_BYTES];
        try (InputStream in = body.byteStream(); OutputStream out = Files.newOutputStream(partFile, options)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                long now = System.currentTimeMillis();
                if (now - lastProgressAt >= PROGRESS_CALLBACK_INTERVAL_MS) {
                    progress.accept(downloaded);
                    lastProgressAt = now;
                }
                if (expectedTotal > 0 && downloaded > expectedTotal) {
                    throw new IOException("下载内容超过 Content-Range 声明长度");
                }
            }
        }
        progress.accept(downloaded);
        logDownloadProgress(downloaded, expectedTotal, partFile.getFileName());
        return downloaded;
    }

    private static void logDownloadProgress(long downloaded, long total, Path filename) {
        if (total > 0) {
            log.info("⏬ 下载进度: {}/{} 字节 ({}%) - {}", downloaded, total,
                    String.format("%.1f", downloaded * 100.0 / total), filename);
        } else {
            log.info("⏬ 下载完成: {} 字节 - {}", downloaded, filename);
        }
    }

    /**
     * 把完整分片替换为最终文件，再清理续传元数据，只在成功下载后发布文件。
     */
    private static long publishCompletedDownload(Path partFile, Path metadataFile, Path target, long totalBytes)
            throws IOException {
        try {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(metadataFile);
        log.info("✅ 流式下载完成，共 {} 字节: {}", totalBytes, target);
        return totalBytes;
    }

    /**
     * 解析源站返回的字节范围，供续传逻辑校验偏移和总长度。
     */
    private static ContentRange parseContentRange(String value) {
        if (value == null || !value.startsWith("bytes ")) return null;
        try {
            String[] sections = value.substring(6).split("/", 2);
            String[] bounds = sections[0].split("-", 2);
            if (sections.length != 2 || bounds.length != 2 || "*".equals(sections[1])) return null;
            return new ContentRange(Long.parseLong(bounds[0]), Long.parseLong(bounds[1]), Long.parseLong(sections[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long parseUnsatisfiedRangeTotal(String value) {
        if (value == null || !value.startsWith("bytes */")) return -1;
        try {
            return Long.parseLong(value.substring("bytes */".length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isHtmlResponse(Response response) {
        String contentType = response.header("Content-Type");
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html");
    }

    private static String responseValidator(Response response) {
        String etag = response.header("ETag");
        return StrUtil.isNotBlank(etag) ? etag : response.header("Last-Modified");
    }

    /**
     * 读取上次保存的响应校验信息，缺失或无法解析时按没有可用续传信息处理。
     */
    private static ResumeMetadata loadResumeMetadata(Path file) {
        if (!Files.isRegularFile(file)) return ResumeMetadata.EMPTY;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            long total = Long.parseLong(properties.getProperty("total", "-1"));
            return new ResumeMetadata(properties.getProperty("etag"), properties.getProperty("lastModified"), total);
        } catch (Exception e) {
            log.warn("断点元数据读取失败，将仅按本地长度尝试续传: {}", e.getMessage());
            return ResumeMetadata.EMPTY;
        }
    }

    /**
     * 保存本次响应的校验标记和总长度，供下载中断后验证分片是否仍可复用。
     */
    private static void saveResumeMetadata(Path file, ResumeMetadata metadata) throws IOException {
        Properties properties = new Properties();
        if (metadata.etag() != null) properties.setProperty("etag", metadata.etag());
        if (metadata.lastModified() != null) properties.setProperty("lastModified", metadata.lastModified());
        properties.setProperty("total", Long.toString(metadata.total()));
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(writer, "resumable download metadata");
        }
    }

    /**
     * 清除不再可信的下载分片及其元数据，准备从头重新下载。
     */
    private static void resetPartialDownload(Path partFile, Path metadataFile) throws IOException {
        Files.deleteIfExists(partFile);
        Files.deleteIfExists(metadataFile);
    }

    private record ContentRange(long start, long end, long total) {
    }

    private record ResumeMetadata(String etag, String lastModified, long total) {
        private static final ResumeMetadata EMPTY = new ResumeMetadata(null, null, -1);

        String validator() {
            return StrUtil.isNotBlank(etag) ? etag : (StrUtil.isNotBlank(lastModified) ? lastModified : null);
        }
    }

    /**
     * 429/503 退避等待：优先使用 Retry-After 头，否则按 2s × 2^n 指数退避，上限 60s。
     */
    private static long backoffWaitMs(Response response, int attempt) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Math.min(Math.max(seconds, 1) * 1000, 60_000);
            } catch (NumberFormatException ignored) {
                // 非数字，走指数退避
            }
        }
        long base = 2000L << Math.min(attempt, 4);
        return Math.min(base, 60_000);
    }

    /**
     * 执行可被线程中断的等待，确保工作流取消不会被退避等待吞掉。
     */
    private static void sleepInterruptibly(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("下载等待被中断");
        }
    }

    private void coolDown(ClientEntry entry) {
        long cooldownMs = TimeUnit.SECONDS.toMillis(
                netConfig.getRateLimit() != null ? netConfig.getRateLimit().getProxyCooldownSeconds() : 300);
        entry.coolDownUntil = System.currentTimeMillis() + cooldownMs;
    }

    /**
     * 添加源站请求共用的浏览器标识等请求头，供不同请求类型复用。
     */
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
