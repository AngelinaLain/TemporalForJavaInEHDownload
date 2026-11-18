package com.checker.manager.source;

import com.checker.dedupe.PerceptualHash;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceResult;
import com.checker.infrastructure.network.EhNetworkClient;
import com.checker.shared.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 将源站网络读取留在抓取节点；调用方负责数据库更新和本地归档比较。 */
@Component
public class GallerySourceManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9]+");
    private static final Pattern GALLERY_PATH = Pattern.compile("/g/([1-9][0-9]*)/([a-zA-Z0-9]+)/?");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*['\"]?([^'\")]+)");
    private static final long MAX_COVER_BYTES = 10L * 1024 * 1024;
    private final EhNetworkClient client;

    public GallerySourceManager(EhNetworkClient client) {
        this.client = client;
    }

    public GallerySourceResult collectGallerySource(GallerySourceRequest request) throws IOException {
        Source source = validate(request);
        return switch (request.kind()) {
            case TAGS -> new GallerySourceResult(fetchTags(source), null);
            case COVER -> new GallerySourceResult(null, fetchCover(source));
        };
    }

    private List<String> fetchTags(Source source) throws IOException {
        String body = JSON.writeValueAsString(Map.of("method", "gdata", "namespace", 1,
                "gidlist", List.of(List.of(source.gid(), source.token()))));
        JsonNode response = JSON.readTree(client.postJson(Constants.EHENTAI_API_URL, body));
        JsonNode metadata = response == null ? null : response.get("gmetadata");
        if (metadata == null || metadata.isNull() || metadata.isEmpty()) return null;
        if (!metadata.isArray()) throw new IOException("EH 元数据格式无效");
        JsonNode gallery = metadata.get(0);
        if (gallery == null || !gallery.isObject()) throw new IOException("EH 画廊元数据格式无效");
        JsonNode gid = gallery.get("gid");
        if (gid != null && !gid.isNull() && !Long.toString(source.gid()).equals(gid.asText())) {
            throw new IOException("EH 元数据与请求画廊不一致");
        }
        JsonNode tags = gallery.get("tags");
        if (tags == null || tags.isNull()) return null;
        if (!tags.isArray()) throw new IOException("EH 标签格式无效");
        List<String> result = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (!tag.isTextual()) throw new IOException("EH 标签格式无效");
            result.add(tag.textValue());
        }
        return List.copyOf(result);
    }

    private GalleryPageFingerprint fetchCover(Source source) throws IOException {
        Document document = Jsoup.parse(client.getHtml(source.url()), source.url());
        List<String> imageUrls = extractSourceCoverUrls(document).stream().limit(6).toList();
        Throwable firstFailure = null;
        for (int index = 0; index < imageUrls.size(); index++) {
            try {
                byte[] bytes = client.getBytes(imageUrls.get(index), MAX_COVER_BYTES);
                GalleryPageFingerprint fingerprint = PerceptualHash.fingerprint(
                        new ByteArrayInputStream(bytes), source.gid(), index, "source-cover", "EH_COVER");
                if (fingerprint != null) return fingerprint;
            } catch (Exception failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        throw new IOException("EH 页面没有可计算的源封面", firstFailure);
    }

    /** 保持原有元数据、CSS 封面及缩略图候选顺序。 */
    static List<String> extractSourceCoverUrls(Document document) {
        if (document == null) return List.of();
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Element meta : document.select("meta[property=og:image], meta[name=twitter:image]")) {
            addSourceUrl(urls, document, meta.attr("content"));
        }
        for (Element styled : document.select("#gd1[style*=url], #gd1 [style*=url]")) {
            var matcher = CSS_URL.matcher(styled.attr("style"));
            while (matcher.find()) addSourceUrl(urls, document, matcher.group(1));
        }
        for (Element image : document.select("#gdt img, .gdtm img, .gdtl img")) {
            addSourceUrl(urls, document, image.attr("data-src"));
            addSourceUrl(urls, document, image.attr("src"));
        }
        return List.copyOf(urls);
    }

    private static void addSourceUrl(Set<String> urls, Document document, String value) {
        if (value == null || value.isBlank() || value.startsWith("data:")) return;
        String resolved = value.trim();
        try {
            if (!document.baseUri().isBlank()) resolved = URI.create(document.baseUri()).resolve(resolved).toString();
        } catch (IllegalArgumentException ignored) {
            // 无法规范化的源站候选交给网络客户端报告读取失败。
        }
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) urls.add(resolved);
    }

    private static Source validate(GallerySourceRequest request) {
        if (request == null || request.gid() <= 0 || request.kind() == null) {
            throw new IllegalArgumentException("源站请求缺少有效画廊或读取类型");
        }
        String token = request.token() == null ? "" : request.token().trim();
        if (!token.isEmpty() && !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("画廊 token 格式无效");
        }
        String url = request.galleryUrl();
        if (url == null || url.isBlank()) {
            if (token.isEmpty()) throw new IllegalArgumentException("数据库没有 EH 地址或 token");
            return new Source(request.gid(), token, Constants.EHENTAI_BASE_URL + "g/" + request.gid() + "/" + token + "/");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("画廊地址无效");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !Set.of("e-hentai.org", "exhentai.org").contains(host)
                || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("仅允许 EH/EX 画廊地址");
        }
        var path = GALLERY_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!path.matches() || !path.group(1).equals(Long.toString(request.gid()))
                || !token.isEmpty() && !token.equals(path.group(2))) {
            throw new IllegalArgumentException("画廊地址与 GID/token 不一致");
        }
        return new Source(request.gid(), path.group(2), "https://" + host + "/g/" + request.gid() + "/" + path.group(2) + "/");
    }

    private record Source(long gid, String token, String url) {}
}
