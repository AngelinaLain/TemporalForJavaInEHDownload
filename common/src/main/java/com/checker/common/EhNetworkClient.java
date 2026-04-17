package com.checker.common;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
import io.temporal.failure.ApplicationFailure;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * EHentai 网络客户端：使用 OkHttp 重构，完美解决 HTTPS 代理隧道 (CONNECT) 407 问题，
 * 且代理认证仅对当前 OkHttpClient 实例生效，不污染 JVM 全局环境。
 */
@Slf4j
@Component
public class EhNetworkClient {

    @Autowired
    private EhNetworkConfig netConfig;

    private OkHttpClient httpClient;

    private static final int TIMEOUT_SECONDS = 15;

    @PostConstruct
    public void init() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        EhNetworkConfig.Proxy proxyConfig = netConfig.getProxy();
        if (StrUtil.isNotBlank(proxyConfig.getHost()) && proxyConfig.getPort() != null) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()));
            builder.proxy(proxy);

            if (StrUtil.isNotBlank(proxyConfig.getUsername()) && StrUtil.isNotBlank(proxyConfig.getPassword())) {
                builder.proxyAuthenticator((route, response) -> {
                    String credential = Credentials.basic(proxyConfig.getUsername(), proxyConfig.getPassword());
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                });
                log.info("✅ OkHttp 代理认证已配置，安全隔离不污染全局");
            }
        }
        this.httpClient = builder.build();
    }

    public String getHtml(String url) {
        log.info("正在请求 EHentai 页面: {}", url);
        Request request = buildBaseRequest(url).get().build();
        return executeRequest(url, request);
    }

    public String postForm(String url, Map<String, Object> formParams) {
        log.info("正在提交 EHentai 表单: {}", url);
        FormBody.Builder formBuilder = new FormBody.Builder();
        if (formParams != null) {
            formParams.forEach((k, v) -> formBuilder.add(k, String.valueOf(v)));
        }
        Request request = buildBaseRequest(url).post(formBuilder.build()).build();
        return executeRequest(url, request);
    }

    public String postJson(String url, String jsonBody) {
        log.info("正在提交 JSON 请求: {}", url);
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        Request request = buildBaseRequest(url).post(body).build();
        return executeRequest(url, request);
    }

    private String executeRequest(String url, Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            return handleResponse(url, response);
        } catch (Exception e) {
            if (e instanceof ApplicationFailure) {
                throw (ApplicationFailure) e;
            }
            log.error("网络请求发生未知致命异常: {}", e.getMessage());
            throw ApplicationFailure.newFailure("代理失效或网络无法连接: " + e.getMessage(), ErrorType.NETWORK_ERROR.getCode());
        }
    }

    private String handleResponse(String url, Response response) throws IOException {
        int status = response.code();
        if (status == 509) {
            log.error("配额超限 (509 Bandwidth Exceeded) - URL: {}", url);
            throw ApplicationFailure.newFailure("触发 509 配额超限", ErrorType.QUOTA_EXCEEDED.getCode());
        } else if (status == 403 || status == 502) {
            log.error("IP 被封禁或节点不可用 - URL: {}", url);
            throw ApplicationFailure.newFailure("IP 被封禁或节点不可用", ErrorType.IP_BANNED.getCode());
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

    private Request.Builder buildBaseRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("Cookie", netConfig.getCookies().getFullCookieString())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7");
    }
}
