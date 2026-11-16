package com.checker.rss;

import com.checker.common.EhSearchQueryBuilder;
import com.checker.dto.RssSearchItem;
import com.checker.dto.SearchOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.ByteArrayInputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class RssFeedClient {
    private static final Pattern LINK = Pattern.compile("https://e-hentai\\.org/g/(\\d+)/([a-zA-Z0-9]+)/?");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RssConfig config;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(90)).followRedirects(false).build();
    public RssFeedClient(RssConfig config) { this.config = config; }

    public List<RssSearchItem> fetch(SearchOptions options) {
        String url = config.getHubUrl().replaceAll("/+$", "") + EhSearchQueryBuilder.rssRoute(options);
        try (var response = client.newCall(new Request.Builder().url(url).header("Accept", "application/rss+xml").build()).execute()) {
            if (!response.isSuccessful()) {
                byte[] error = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(MAX_ERROR_BYTES);
                throw new IllegalStateException(httpError(response.code(), response.header("Content-Type", ""), error));
            }
            if (response.body() == null) throw new IllegalStateException("RSSHub 返回空响应（HTTP " + response.code() + "）");
            byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("RSS 响应超过 4 MiB 限制");
            return parse(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("RSS 拉取失败：" + e.getMessage(), e);
        }
    }

    /** RSSHub renders upstream failures as 503 HTML; keep the actionable cause, never raw HTML or credentials. */
    static String httpError(int status, String contentType, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        String detail = "";
        if (contentType.toLowerCase(Locale.ROOT).contains("json") || text.startsWith("{")) {
            try {
                var root = JSON.readTree(text);
                detail = root.path("error").path("message").asText("");
                if (detail.isBlank()) detail = root.path("message").asText("");
            } catch (Exception ignored) { /* A truncated/error response is still an HTTP failure. */ }
        } else if (contentType.toLowerCase(Locale.ROOT).contains("html") || text.startsWith("<")) {
            var document = Jsoup.parse(text);
            document.select("script,style,noscript").remove();
            var message = document.selectFirst("code.details, pre");
            detail = message == null ? document.body().text() : message.text();
        } else detail = text;

        detail = redact(detail).replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        String hint = "";
        if (detail.contains("Proxy response (403)")) hint = "；代理拒绝 HTTPS 隧道（CONNECT 403），请检查代理认证和访问规则";
        else if (detail.contains("Proxy response (407)") || status == 407) hint = "；代理认证失败，请检查代理用户名和密码";
        else if (detail.contains("ENOTFOUND") || detail.contains("EAI_AGAIN")) hint = "；域名解析失败，请检查容器 DNS 与代理地址";
        else if (detail.contains("ECONNREFUSED")) hint = "；连接被拒绝，请检查代理或目标服务地址与端口";
        String suffix = detail.isBlank() ? "；请查看 RSSHub 日志获取具体原因"
                : "；详情：" + detail.substring(0, Math.min(800, detail.length()));
        return "RSSHub 返回 HTTP " + status + hint + suffix;
    }

    private static String redact(String text) {
        return text
                .replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^\\s<>\"']*@", "$1[REDACTED]@")
                .replaceAll("(?i)(?:proxy-authorization|authorization)\\s*[:=]\\s*(?:Basic|Bearer)\\s+[^\\s,;<]+", "Authorization: [REDACTED]")
                .replaceAll("(?i)(?:set-cookie|cookie)\\s*[:=][^\\r\\n]*", "Cookie: [REDACTED]")
                .replaceAll("(?i)((?:ipb_member_id|ipb_pass_hash|igneous|sk|star|token|api[_-]?key|password|passwd|proxy_auth)\\s*[:=]\\s*)[^\\s&,;<>]+", "$1[REDACTED]");
    }

    static List<RssSearchItem> parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        if (!"rss".equals(doc.getDocumentElement().getTagName()) || doc.getElementsByTagName("channel").getLength() != 1) throw new IllegalArgumentException("无效 RSS 文档");
        var nodes = doc.getElementsByTagName("item");
        var items = new LinkedHashMap<Long, RssSearchItem>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String url = text(item, "link");
            var matcher = LINK.matcher(url);
            if (!matcher.matches()) throw new IllegalArgumentException("RSS 包含非 EH 画廊链接");
            long gid = Long.parseLong(matcher.group(1));
            long published = ZonedDateTime.parse(text(item, "pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            items.putIfAbsent(gid, new RssSearchItem(gid, matcher.group(2), url, text(item, "title"), published));
        }
        return List.copyOf(items.values());
    }

    private static String text(Element parent, String key) {
        var nodes = parent.getElementsByTagName(key);
        if (nodes.getLength() != 1) throw new IllegalArgumentException("RSS 条目缺少 " + key);
        return nodes.item(0).getTextContent().trim();
    }
}
