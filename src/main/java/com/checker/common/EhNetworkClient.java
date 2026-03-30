package com.checker.common;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.*;
import com.checker.config.EhNetworkConfig;
import io.temporal.failure.ApplicationFailure;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Map;

/**
 * EHentai 网络客户端：封装了带代理、Cookie、UA 伪装的 HTTP 请求能力
 */
@Slf4j
@Component
public class EhNetworkClient {

    @Autowired
    private EhNetworkConfig netConfig;

    // 默认超时时间设定为 15 秒（爬虫尽量设置长一点防抖）
    private static final int TIMEOUT_MS = 15000;

    /**
     * 初始化全局代理认证器，在 Bean 创建后自动执行
     * <p>仅在配置了代理账号密码时注册 {@link Authenticator}，且严格限定只响应代理认证请求</p>
     */
    @PostConstruct
    public void initProxyAuth() {
        EhNetworkConfig.Proxy proxyConfig = netConfig.getProxy();
        if (StrUtil.isNotBlank(proxyConfig.getUsername()) && StrUtil.isNotBlank(proxyConfig.getPassword())) {
            // 注册全局认证器，且严格限定仅响应代理认证请求
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    // 只有当请求者是 PROXY (代理) 时，才交出账号密码，避免泄露给其他服务器
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(proxyConfig.getUsername(), proxyConfig.getPassword().toCharArray());
                    }
                    return null; // 其他普通网站的弹窗认证一律不理会
                }
            });
            log.info("✅ 全局代理认证器已成功初始化");
        }
    }

    /**
     * 发起 GET 请求并返回 HTML 内容
     *
     * @param url 目标链接
     * @return 页面 HTML 字符串
     */
    public String getHtml(String url) {
        log.info("正在请求 EHentai 页面: {}", url);
        try (HttpResponse response = buildBaseRequest(url, Method.GET).execute()) {
            return handleResponse(url, response);
        } catch (Exception e) {
        // 如果已经是我们自定义包装的 Temporal 业务异常，直接抛出，不要拦截！
        if (e instanceof ApplicationFailure) {
            throw (ApplicationFailure) e;
        }
        log.error("网络请求发生未知致命异常: {}", e.getMessage());
        throw ApplicationFailure.newFailure("代理失效或网络无法连接: " + e.getMessage(), ErrorType.NETWORK_ERROR.getCode());
    }
    }

    /**
     * 发起 POST 表单请求并返回 HTML 内容
     */
    public String postForm(String url, Map<String, Object> formParams) {
        log.info("正在提交 EHentai 表单: {}", url);
        try {
            HttpRequest request = buildBaseRequest(url, Method.POST);
            if (formParams != null && !formParams.isEmpty()) {
                request.form(formParams);
            }
            try (HttpResponse response = request.execute()) {
                return handleResponse(url, response);
            }
        } catch (Exception e) {
            if (e instanceof ApplicationFailure) {
                throw (ApplicationFailure) e;
            }
            log.error("表单发生未知致命异常: {}", e.getMessage());
            throw ApplicationFailure.newFailure("代理失效或网络无法连接: " + e.getMessage(), ErrorType.NETWORK_ERROR.getCode());
        }
    }

    /**
     * 统一处理 HTTP 响应，检测 509 配额超限、403/502 封禁等异常状态码
     *
     * @param url      请求 URL（用于日志输出）
     * @param response Hutool HTTP 响应对象
     * @return 响应体字符串
     * @throws ApplicationFailure 当遇到 509 / 403 / 502 或其他非 200 状态码时抛出
     */
    private String handleResponse(String url, HttpResponse response) {
        int status = response.getStatus();
        if (status == 509) {
            log.error("配额超限 (509 Bandwidth Exceeded) - URL: {}", url);
            throw ApplicationFailure.newFailure("触发 509 配额超限", ErrorType.QUOTA_EXCEEDED.getCode());
        } else if (status == 403 || status == 502) { // 502 网关错误或 403 封禁
            log.error("IP 被封禁或节点不可用 - URL: {}", url);
            throw ApplicationFailure.newFailure("IP 被封禁或节点不可用", ErrorType.IP_BANNED.getCode());
        }
        if (!response.isOk()) {
            log.error("请求失败，HTTP 状态码: {} - URL: {}", status, url);
            throw new RuntimeException("请求异常: HTTP " + status);
        }
        return response.body();
    }

    /**
     * 构建带有完整伪装、代理、Cookie 的 Hutool Request
     */
    private HttpRequest buildBaseRequest(String url, Method method) {
        HttpRequest request = HttpUtil.createRequest(method, url)
                .timeout(TIMEOUT_MS)
                .cookie(netConfig.getCookies().getFullCookieString())
                // 必须带上浏览器 UA，这是 params.md 里明确要求的
                .header(Header.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header(Header.ACCEPT_LANGUAGE, "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7");
        // 注入代理配置
        EhNetworkConfig.Proxy proxyConfig = netConfig.getProxy();
        if (StrUtil.isNotBlank(proxyConfig.getHost()) && proxyConfig.getPort() != null) {
            request.setHttpProxy(proxyConfig.getHost(), proxyConfig.getPort());
        }
        return request;
    }
}
