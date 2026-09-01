package com.checker.temporalServices;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checker.common.Constants;
import com.checker.common.DownloadStatus;
import com.checker.common.GalleryDeduplication;
import com.checker.common.EhNetworkClient;
import com.checker.common.ErrorType;
import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.temporalServices.activities.ScraperActivity;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.Data;
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
import java.util.stream.Collectors;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬虫 Activity 实现：负责 EHentai 画廊列表抓取与下载链接提取
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class ScraperActivityImpl implements ScraperActivity {

    @Autowired
    private EhNetworkClient ehNetworkClient;

    private static final String STATUS_PENDING = DownloadStatus.PENDING.getValue();
    private static final Pattern GALLERY_PATTERN = Pattern.compile("https://e-hentai\\.org/g/(\\d+)/([a-z0-9]+)/");
    private static final int DELAY_MS = 3000;
    private static final int MAX_SAFE_PAGES = 100; // 防止网站结构突变导致无限死循环的安全阈值
    private static final int METADATA_BATCH_SIZE = 25; // EH gdata 单次请求上限

    @Override
    public List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions) {
        List<EhGalleriesEntity> allResults = new ArrayList<>();
        String currentUrl = buildInitialSearchUrl(searchOptions);
        int pageNo = 1;
        String lastNextCursor = currentUrl;

        // 调度器：负责分页流转、心跳与节流
        while (StrUtil.isNotBlank(currentUrl) && pageNo <= MAX_SAFE_PAGES) {
            // 向 Temporal Server 汇报心跳，防止长耗时 Activity 被误判超时
            Activity.getExecutionContext().heartbeat(pageNo);
            log.info("正在抓取第 {} 页: {}", pageNo, currentUrl);

            // 抓取单页数据
            PageScrapeResult pageResult = scrapeSinglePage(currentUrl, searchOptions, pageNo);
            allResults.addAll(pageResult.getGalleries());

            // 终止条件判断
            if (!pageResult.isHasData() || !pageResult.isHasNextPage()) {
                String stopReason = !pageResult.isHasData() ? "no_data_on_page" : "end_of_pages";
                log.info("停止翻页，原因: {}", stopReason);
                allResults.forEach(r -> r.setTraceStopReason(stopReason));
                break;
            }

            // 推进到下一页
            currentUrl = pageResult.getNextUrl();
            lastNextCursor = currentUrl;
            pageNo++;

            // 礼貌抓取：翻页间歇延迟
            ThreadUtil.sleep(DELAY_MS);
        }

        // 安全保护触发的日志
        if (pageNo > MAX_SAFE_PAGES) {
            log.warn("达到最大安全翻页限制 {}，停止抓取，防止死循环。", MAX_SAFE_PAGES);
            allResults.forEach(r -> r.setTraceStopReason("max_safe_pages_reached"));
        }

        // 统一设置最后一次的游标
        String finalCursor = lastNextCursor;
        allResults.forEach(r -> r.setTraceLastNextCursor(finalCursor));

        enrichGalleryMetadata(allResults);
        log.info("抓取完成，共提取到 {} 个有效画廊。", allResults.size());
        return allResults;
    }

    /**
     * 在下载排队前通过 EH gdata 补全作品识别需要的元数据。
     * 元数据查询失败不会阻断普通抓取，只会跳过本次作品级去重。
     */
    private void enrichGalleryMetadata(List<EhGalleriesEntity> galleries) {
        List<EhGalleriesEntity> candidates = galleries.stream()
                .filter(gallery -> gallery.getGid() != null && StrUtil.isNotBlank(gallery.getToken()))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        Map<Long, EhGalleriesEntity> galleriesByGid = candidates.stream()
                .collect(Collectors.toMap(EhGalleriesEntity::getGid, gallery -> gallery, (left, right) -> left));

        for (int offset = 0; offset < candidates.size(); offset += METADATA_BATCH_SIZE) {
            List<EhGalleriesEntity> batch = candidates.subList(offset, Math.min(offset + METADATA_BATCH_SIZE, candidates.size()));
            try {
                JSONObject request = JSONUtil.createObj();
                request.set("method", "gdata");
                request.set("namespace", 1);
                request.set("gidlist", JSONUtil.parseArray(batch.stream()
                        .map(gallery -> List.of(gallery.getGid(), gallery.getToken()))
                        .toList()));

                JSONObject response = JSONUtil.parseObj(ehNetworkClient.postJson(Constants.EHENTAI_API_URL, request.toString()));
                JSONArray metadataList = response.getJSONArray("gmetadata");
                if (metadataList == null || metadataList.isEmpty()) {
                    log.warn("EH gdata 未返回元数据，本批 {} 条将不参与作品级去重", batch.size());
                    continue;
                }

                for (int index = 0; index < metadataList.size(); index++) {
                    JSONObject metadata = metadataList.getJSONObject(index);
                    Long gid = metadata.getLong("gid");
                    EhGalleriesEntity gallery = galleriesByGid.get(gid);
                    if (gallery == null) {
                        continue;
                    }
                    gallery.setOriginalTitle(StrUtil.blankToDefault(metadata.getStr("title_jpn"), null));
                    gallery.setPageCount(parseInteger(metadata.getStr("filecount")));
                    gallery.setRating(parseDouble(metadata.getStr("rating")));
                    JSONArray tags = metadata.getJSONArray("tags");
                    if (tags != null) {
                        gallery.setTags(tags.toList(String.class));
                    }
                    GalleryDeduplication.populateIdentity(gallery);
                }
            } catch (Exception exception) {
                log.warn("EH gdata 元数据查询失败，本批 {} 条将按普通画廊处理: {}", batch.size(), exception.getMessage());
            }
        }

        long identified = galleries.stream().filter(GalleryDeduplication::isIdentifiable).count();
        log.info("作品级去重元数据补全完成：{}/{} 条具备可靠作品指纹", identified, galleries.size());
    }

    private Integer parseInteger(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
    /**
     * 提取出的 URL 拼接逻辑
     */
    private String buildInitialSearchUrl(SearchOptions searchOptions) {
        String searchParam = URLUtil.encodeAll(searchOptions.getKeyword());
        if (StrUtil.isNotBlank(searchOptions.getLanguage())) {
            searchParam += "%20language%3A%22" + URLUtil.encodeAll(searchOptions.getLanguage()) + "%22";
        }

        StringBuilder urlBuilder = new StringBuilder(
                String.format("%s?f_search=%s&f_cats=%d&advsearch=1",
                        Constants.EHENTAI_BASE_URL, searchParam, searchOptions.getFilterCats())
        );

        if (searchOptions.getMinimumRating() != null && searchOptions.getMinimumRating() > 1) {
            urlBuilder.append("&f_srdd=").append(searchOptions.getMinimumRating());
        }
        if (searchOptions.getPageAtLeast() != null && searchOptions.getPageAtLeast() > 0) {
            urlBuilder.append("&f_spf=").append(searchOptions.getPageAtLeast());
        }
        if (searchOptions.getPageAtMost() != null && searchOptions.getPageAtMost() > 0) {
            urlBuilder.append("&f_spt=").append(searchOptions.getPageAtMost());
        }
        if (Boolean.TRUE.equals(searchOptions.getSearchExpungedGalleries())) {
            urlBuilder.append("&f_sh=on");
        }
        if (Boolean.TRUE.equals(searchOptions.getShowOnlyWithTorrents())) {
            urlBuilder.append("&f_sto=on");
        }
        if (Boolean.TRUE.equals(searchOptions.getDisableLanguageFilter())) {
            urlBuilder.append("&f_sfl=on");
        }
        if (Boolean.TRUE.equals(searchOptions.getDisableUploaderFilter())) {
            urlBuilder.append("&f_sfu=on");
        }
        if (Boolean.TRUE.equals(searchOptions.getDisableTagsFilter())) {
            urlBuilder.append("&f_sft=on");
        }

        return urlBuilder.toString();
    }

    /**
     * 提取出的单页抓取与解析逻辑
     */
    private PageScrapeResult scrapeSinglePage(String url, SearchOptions searchOptions, int pageNo) {
        PageScrapeResult result = new PageScrapeResult();
        String html = ehNetworkClient.getHtml(url);
        Document doc = Jsoup.parse(html);

        Elements galleryLinks = doc.select("a[href~=https://e-hentai\\.org/g/\\d+/[a-z0-9]+/]");

        for (Element link : galleryLinks) {
            Element glinkDiv = link.selectFirst("div.glink");
            if (glinkDiv == null) continue;

            String href = link.attr("href");
            Matcher matcher = GALLERY_PATTERN.matcher(href);
            if (!matcher.find()) continue;

            result.setHasData(true);

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
            result.getGalleries().add(entity);
        }

        // 解析下一页
        Element nextLink = doc.selectFirst("#unext");
        String nextUrl = nextLink != null ? nextLink.attr("href") : null;

        if (StrUtil.isNotBlank(nextUrl) && !nextUrl.equals(url) && !"#".equals(nextUrl)) {
            result.setHasNextPage(true);
            result.setNextUrl(nextUrl.replace("&amp;", "&"));
        }

        return result;
    }

    @Override
    public ArchiveDownloadInfo extractDownloadUrl(Long gid, String token) {
        String archiveUrl = String.format("%s?gid=%d&token=%s", Constants.EHENTAI_ARCHIVER_URL, gid, token);
        ArchiveDownloadInfo info = new ArchiveDownloadInfo();

        // 心跳上报：直链提取涉及两次串行 HTTP 调用 + 多次正则解析，耗时可能超过心跳超时阈值
        Activity.getExecutionContext().heartbeat("正在获取归档页面元数据: " + gid);

        // 1. 🌟 先发起 GET 请求，获取归档页面元数据（包含文件大小）
        log.info("正在获取归档页面元数据: {}", archiveUrl);
        String prepareHtml = ehNetworkClient.getHtml(archiveUrl);
        Document doc = Jsoup.parse(prepareHtml);

        double finalSizeMb = 0.0;

        // 【模式一】：尝试从 H@H Downloader 区域获取
        Element orgTd = doc.selectFirst("td:has(a[onclick*=do_hathdl('org')])");
        if (orgTd != null) {
            String tdText = orgTd.text();
            Pattern p = Pattern.compile("([\\d.]+)\\s*(MB|GB|KB|MiB|GiB)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(tdText);
            if (m.find()) {
                finalSizeMb = parseSizeToMb(m.group(1), m.group(2));
                log.info("📊 从 H@H 区域解析到大小: {} MB", finalSizeMb);
            }
        }

        // 【模式二】：如果模式一失败（大小仍为 0.0），安全回退到之前的全文匹配模式
        if (finalSizeMb <= 0.0) {
            log.info("⚠️ H@H 区域未能获取到大小，回退到全文匹配模式...");

            // 兼容普通空格、HTML 实体 (&nbsp;) 和可能穿插的 <strong> 标签
            String sizeRegex = "Estimated Size:[\\s\\u00A0]*(<[^>]+>)*[\\s\\u00A0]*([\\d.]+)[\\s\\u00A0]*(MB|GB|KB|MiB|GiB)";
            Matcher fallbackMatcher = Pattern.compile(sizeRegex, Pattern.CASE_INSENSITIVE).matcher(prepareHtml);

            if (fallbackMatcher.find()) {
                finalSizeMb = parseSizeToMb(fallbackMatcher.group(2), fallbackMatcher.group(3));
                log.info("📊 从全文回退模式解析到大小: {} MB", finalSizeMb);
            } else {
                // 【模式三兜底】：直接清洗掉所有 HTML 标签后当纯文本暴力匹配
                String cleanText = doc.text();
                Matcher pureTextMatcher = Pattern.compile("Estimated Size:\\s*([\\d.]+)\\s*(MB|GB|KB|MiB|GiB)", Pattern.CASE_INSENSITIVE).matcher(cleanText);
                if (pureTextMatcher.find()) {
                    finalSizeMb = parseSizeToMb(pureTextMatcher.group(1), pureTextMatcher.group(2));
                    log.info("📊 从纯文本兜底模式解析到大小: {} MB", finalSizeMb);
                } else {
                    log.warn("⚠️ 无法在页面任何位置找到文件大小信息，大小将默认为 0.0");
                }
            }
        }

        info.setEstimatedSizeMb(finalSizeMb);

        Activity.getExecutionContext().heartbeat("正在提交下载表单获取直链: " + gid);

        // 2. 🌟 发起 POST 请求，模拟点击下载获取真实直链
        Map<String, Object> form = new HashMap<>();
        form.put("dlcheck", "Download Original Archive");
        form.put("dltype", "org");

        String postHtml = ehNetworkClient.postForm(archiveUrl, form);
        Document postDoc = Jsoup.parse(postHtml);

        // JS redirect 只能用正则
        String jsRedirect = ReUtil.getGroup1("document\\.location\\s*=\\s*['\"](https?://[^'\"]+)['\"]", postHtml);
        // HTML 链接用 Jsoup
        Element clickEl = postDoc.selectFirst("a:contains(Click Here To Start Downloading)");
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

        info.setDownloadUrl(finalUrl);
        return info;
    }

    /**
     * 辅助方法：统一将不同单位的容量文本转换为 MB 浮点数
     */
    private double parseSizeToMb(String valueStr, String unitStr) {
        double size = Double.parseDouble(valueStr);
        String unit = unitStr.toUpperCase();
        if (unit.contains("G")) {
            size *= 1024;
        } else if (unit.contains("K")) {
            size /= 1024;
        }
        return size;
    }

    /**
     * 内部 DTO：用于在单页解析方法与主调度器之间传递解析状态
     */
    @Data
    private static class PageScrapeResult {
        private List<EhGalleriesEntity> galleries = new ArrayList<>();
        private boolean hasData = false;
        private boolean hasNextPage = false;
        private String nextUrl;
    }
}