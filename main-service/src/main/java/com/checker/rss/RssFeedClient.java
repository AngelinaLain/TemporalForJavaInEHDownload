package com.checker.rss;

import com.checker.common.EhSearchQueryBuilder;
import com.checker.dto.RssSearchItem;
import com.checker.dto.SearchOptions;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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

@Component
public class RssFeedClient {
    private static final Pattern LINK = Pattern.compile("https://e-hentai\\.org/g/(\\d+)/([a-zA-Z0-9]+)/?");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final RssConfig config;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(90)).followRedirects(false).build();
    public RssFeedClient(RssConfig config) { this.config = config; }

    public List<RssSearchItem> fetch(SearchOptions options) {
        String url = config.getHubUrl().replaceAll("/+$", "") + EhSearchQueryBuilder.rssRoute(options);
        try (var response = client.newCall(new Request.Builder().url(url).header("Accept", "application/rss+xml").build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("RSSHub 返回 HTTP " + response.code());
            byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("RSS 响应超过 4 MiB 限制");
            return parse(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("RSS 拉取失败：" + e.getMessage(), e);
        }
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
