package com.checker.manager.search;

import com.checker.dto.RssSearchItem;
import com.checker.dto.RssSearchPage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/** Only extracts the source listing; an error/login page must never advance the coverage cursor. */
public final class EhSearchPageParser {
    private static final Pattern GALLERY = Pattern.compile("https://e-hentai\\.org/g/(\\d+)/([a-zA-Z0-9]+)/?");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private EhSearchPageParser() {}

    public static String validateCursor(String url) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"e-hentai.org".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1 || !"/".equals(uri.getPath()) || uri.getFragment() != null) {
            throw new IllegalArgumentException("非法 EH 分页地址");
        }
        return uri.toASCIIString();
    }

    public static RssSearchPage parse(String html, String url) {
        validateCursor(url);
        var doc = Jsoup.parse(html, url);
        var items = new LinkedHashMap<Long, RssSearchItem>();
        for (Element title : doc.select(".glink")) {
            Element anchor = title.closest("a[href]");
            if (anchor == null) continue;
            var match = GALLERY.matcher(anchor.absUrl("href"));
            if (!match.matches()) continue;
            Element row = anchor;
            while (row != null && !row.tagName().equals("body") && row.selectFirst("[id^=posted_]") == null) row = row.parent();
            Element posted = row == null || row.tagName().equals("body") ? null : row.selectFirst("[id^=posted_]");
            if (posted == null) throw new IllegalStateException("检索结果缺少发布时间，停止推进补查游标");
            long timestamp = LocalDateTime.parse(posted.text().trim(), DATE).toInstant(ZoneOffset.UTC).toEpochMilli();
            long gid = Long.parseLong(match.group(1));
            items.putIfAbsent(gid, new RssSearchItem(gid, match.group(2), anchor.absUrl("href"), title.text(), timestamp));
        }
        if (items.isEmpty() && !doc.text().toLowerCase().contains("no hits found")) {
            throw new IllegalStateException("未识别到有效检索结果或明确空结果，可能登录失效、限流或页面结构变化");
        }
        Element next = doc.selectFirst("#unext[href]");
        String nextUrl = next == null || next.attr("href").equals("#") ? null : next.absUrl("href");
        if (nextUrl != null && !nextUrl.isBlank()) {
            validateCursor(nextUrl);
            if (nextUrl.equals(url)) throw new IllegalStateException("源站返回重复分页游标");
        } else nextUrl = null;
        return new RssSearchPage(items.values().stream().toList(), nextUrl, nextUrl == null);
    }
}
