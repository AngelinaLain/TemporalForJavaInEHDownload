package com.checker.temporalServices.activities.impl;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.EhNetworkClient;
import com.checker.common.ErrorType;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.ScraperActivity;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬虫 Activity 实现：负责 EHentai 画廊列表抓取与下载链接提取
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class ScraperActivityImpl implements ScraperActivity {

    @Autowired
    private EhNetworkClient ehNetworkClient;

    private static final String STATUS_PENDING = DownloadStatus.PENDING.getValue();

    @Override
    public List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions) {
        List<EhGalleriesEntity> results = new ArrayList<>();

        String searchParam = URLUtil.encodeAll(searchOptions.getKeyword());
        if (StrUtil.isNotBlank(searchOptions.getLanguage())) {
            searchParam += "%20language%3A%22" + URLUtil.encodeAll(searchOptions.getLanguage()) + "%22";
        }

        String currentUrl = String.format("%s?f_search=%s&f_cats=%d&advsearch=1",
                Constants.EHENTAI_BASE_URL, searchParam, searchOptions.getFCats());

        if (searchOptions.getMinimumRating() != null && searchOptions.getMinimumRating() > 1) {
            currentUrl += "&f_srdd=" + searchOptions.getMinimumRating();
        }
        if (searchOptions.getPageAtLeast() != null && searchOptions.getPageAtLeast() > 0) {
            currentUrl += "&f_spf=" + searchOptions.getPageAtLeast();
        }
        if (searchOptions.getPageAtMost() != null && searchOptions.getPageAtMost() > 0) {
            currentUrl += "&f_spt=" + searchOptions.getPageAtMost();
        }
        if (Boolean.TRUE.equals(searchOptions.getSearchExpungedGalleries())) {
            currentUrl += "&f_sh=on";
        }
        if (Boolean.TRUE.equals(searchOptions.getShowOnlyWithTorrents())) {
            currentUrl += "&f_sto=on";
        }
        if (Boolean.TRUE.equals(searchOptions.getDisableLanguageFilter())) {
            currentUrl += "&f_sfl=on";
        }
        if (Boolean.TRUE.equals(searchOptions.getDisableUploaderFilter())) {
            currentUrl += "&f_sfu=on";
        }
        if (Boolean.TRUE.equals(searchOptions.getDisableTagsFilter())) {
            currentUrl += "&f_sft=on";
        }

        int maxPages = 10;
        int delayMs = 3000;
        String lastNextCursor = null;
        Pattern galleryPattern = Pattern.compile("https://e-hentai\\.org/g/(\\d+)/([a-z0-9]+)/");

        for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
            // 向 Temporal Server 汇报心跳，防止长耗时 Activity 被误判超时
            Activity.getExecutionContext().heartbeat(pageNo);

            log.info("正在抓取第 {} 页: {}", pageNo, currentUrl);
            String html = ehNetworkClient.getHtml(currentUrl);
            Document doc = Jsoup.parse(html);

            // 使用 Jsoup CSS 选择器替代脆弱的正则表达式
            Elements galleryLinks = doc.select("a[href~=https://e-hentai\\.org/g/\\d+/[a-z0-9]+/]");
            boolean hasData = false;

            for (Element link : galleryLinks) {
                Element glinkDiv = link.selectFirst("div.glink");
                if (glinkDiv == null) continue;

                String href = link.attr("href");
                Matcher matcher = galleryPattern.matcher(href);
                if (!matcher.find()) continue;

                hasData = true;
                EhGalleriesEntity entity = new EhGalleriesEntity();
                entity.setGalleryUrl(href);
                entity.setGid(Long.parseLong(matcher.group(1)));
                entity.setToken(matcher.group(2));
                entity.setTitle(glinkDiv.text());
                entity.setFilename(entity.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_"));
                entity.setSearchQuery(searchOptions.getKeyword());
                entity.setCrawledAt(new Date());
                entity.setDownloadStatus(STATUS_PENDING);
                entity.setTracePagesCrawled(pageNo);
                entity.setTraceFirstPageTitle(doc.title());
                results.add(entity);
            }

            if (!hasData) {
                log.info("当前页未解析到画廊数据，可能已到底部。");
                results.forEach(r -> r.setTraceStopReason("no_data_on_page"));
                break;
            }

            Element nextLink = doc.selectFirst("#unext");
            String nextUrl = nextLink != null ? nextLink.attr("href") : null;
            if (StrUtil.isBlank(nextUrl)) {
                log.info("未找到下一页游标，停止翻页。");
                results.forEach(r -> r.setTraceStopReason("no_next_cursor"));
                break;
            }
            nextUrl = nextUrl.replace("&amp;", "&");
            if (nextUrl.equals(currentUrl)) {
                log.info("下一页游标未变化，停止翻页。");
                results.forEach(r -> r.setTraceStopReason("next_cursor_not_changed"));
                break;
            }
            currentUrl = nextUrl;
            lastNextCursor = currentUrl;

            if (pageNo < maxPages) {
                ThreadUtil.sleep(delayMs);
            }
        }

        String finalCursor = lastNextCursor;
        results.forEach(r -> r.setTraceLastNextCursor(finalCursor));
        log.info("抓取完成，共提取到 {} 个有效画廊。", results.size());
        return results;
    }

    @Override
    public String extractDownloadUrl(Long gid, String token) {
        String archiveUrl = String.format("%s?gid=%d&token=%s", Constants.EHENTAI_ARCHIVER_URL, gid, token);
        Map<String, Object> form = new HashMap<>();
        form.put("dlcheck", "Download Original Archive");
        form.put("dltype", "org");

        String html = ehNetworkClient.postForm(archiveUrl, form);
        Document doc = Jsoup.parse(html);

        // JS redirect 只能用正则
        String jsRedirect = ReUtil.getGroup1("document\\.location\\s*=\\s*['\"](https?://[^'\"]+)['\"]", html);
        // HTML 链接用 Jsoup
        Element clickEl = doc.selectFirst("a:contains(Click Here To Start Downloading)");
        String clickLink = clickEl != null ? clickEl.attr("href") : null;

        String finalUrl = null;
        if (StrUtil.isNotBlank(jsRedirect)) {
            finalUrl = jsRedirect;
        } else if (StrUtil.isNotBlank(clickLink)) {
            finalUrl = clickLink.startsWith("http") ? clickLink : Constants.EHENTAI_BASE_URL + clickLink;
        }

        if (StrUtil.isBlank(finalUrl)) {
            throw ApplicationFailure.newFailure("无法提取下载链接，可能 GP 不足或配额受限", ErrorType.ARCHIVE_LINK_EXTRACT_FAILED.getCode());
        }

        if (!finalUrl.contains("start=1")) {
            finalUrl = finalUrl + (finalUrl.contains("?") ? "&" : "?") + "start=1";
        }
        return finalUrl;
    }
}
