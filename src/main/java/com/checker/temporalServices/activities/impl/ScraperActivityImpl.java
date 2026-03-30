package com.checker.temporalServices.activities.impl;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.checker.common.DownloadStatus;
import com.checker.common.EhNetworkClient;
import com.checker.common.ErrorType;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.ScraperActivity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
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
@ActivityImpl(taskQueues = "EHDownloadTaskQueue")
public class ScraperActivityImpl implements ScraperActivity {

    @Autowired
    private EhNetworkClient ehNetworkClient;

    private static final String STATUS_PENDING = DownloadStatus.PENDING.getValue();

    @Override
    public List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions) {
        List<EhGalleriesEntity> results = new ArrayList<>();
        String baseUrl = "https://e-hentai.org/";

        String searchParam = URLUtil.encodeAll(searchOptions.getKeyword());
        if (StrUtil.isNotBlank(searchOptions.getLanguage())) {
            searchParam += "%20language%3A%22" + URLUtil.encodeAll(searchOptions.getLanguage()) + "%22";
        }

        String currentUrl = String.format("%s?f_search=%s&f_cats=%d&advsearch=1",
                baseUrl, searchParam, searchOptions.getFCats());

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

        for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
            log.info("正在抓取第 {} 页: {}", pageNo, currentUrl);
            String html = ehNetworkClient.getHtml(currentUrl);

            String regex = "<a href=\"(https://e-hentai\\.org/g/(\\d+)/([a-z0-9]+)/)\"[^>]*><div class=\"glink\">([^<]+)</div>";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(html);

            boolean hasData = false;
            while (matcher.find()) {
                hasData = true;
                EhGalleriesEntity entity = new EhGalleriesEntity();
                entity.setGalleryUrl(matcher.group(1));
                entity.setGid(Long.parseLong(matcher.group(2)));
                entity.setToken(matcher.group(3));
                entity.setTitle(matcher.group(4));
                entity.setFilename(entity.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_"));
                entity.setSearchQuery(searchOptions.getKeyword());
                entity.setCrawledAt(new Date());
                entity.setDownloadStatus(STATUS_PENDING);
                entity.setTracePagesCrawled(pageNo);
                entity.setTraceFirstPageTitle(ReUtil.getGroup1("<title>(.*?)</title>", html));
                results.add(entity);
            }

            if (!hasData) {
                log.info("当前页未解析到画廊数据，可能已到底部。");
                results.forEach(r -> r.setTraceStopReason("no_data_on_page"));
                break;
            }

            String nextUrl = ReUtil.getGroup1("href=\"([^\"]+)\"[^>]*id=\"unext\"", html);
            if (nextUrl == null) {
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
        String archiveUrl = String.format("https://e-hentai.org/archiver.php?gid=%d&token=%s", gid, token);
        Map<String, Object> form = new HashMap<>();
        form.put("dlcheck", "Download Original Archive");
        form.put("dltype", "org");

        String html = ehNetworkClient.postForm(archiveUrl, form);
        String jsRedirect = ReUtil.getGroup1("document\\.location\\s*=\\s*['\"](https?://[^'\"]+)['\"]", html);
        String clickLink  = ReUtil.getGroup1("<a href=\"([^\"]+)\">Click Here To Start Downloading</a>", html);

        String finalUrl = null;
        if (StrUtil.isNotBlank(jsRedirect)) {
            finalUrl = jsRedirect;
        } else if (StrUtil.isNotBlank(clickLink)) {
            finalUrl = clickLink.startsWith("http") ? clickLink : "https://e-hentai.org" + clickLink;
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
