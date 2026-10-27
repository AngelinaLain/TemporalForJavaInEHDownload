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
import com.checker.common.PerceptualHash;
import com.checker.common.EhNetworkClient;
import com.checker.common.ErrorType;
import com.checker.dto.ArchiveDownloadInfo;
import com.checker.dto.SearchOptions;
import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryScrapePage;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayInputStream;

/**
 * 执行画廊检索、元数据补全、少量预览指纹采样及归档下载地址提取。
 * 分页时向任务引擎汇报进度，并设置请求间隔和翻页上限，避免异常页面触发持续抓取。
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class ScraperActivityImpl implements ScraperActivity {

    @Autowired
    private EhNetworkClient ehNetworkClient;

    private static final String STATUS_PENDING = DownloadStatus.PENDING.getValue();
    private static final Pattern GALLERY_PATTERN = Pattern.compile("https://e-hentai\\.org/g/(\\d+)/([a-z0-9]+)/");
    private static final Pattern GALLERY_REFERENCE_PATTERN = Pattern.compile(
            "(?i)(?:https?://(?:e-hentai|exhentai)\\.org/)?(?:g/)?(\\d+)[/:]([a-z0-9]+)(?:/.*)?");
    private static final int DELAY_MS = 3000;
    private static final int MAX_SAFE_PAGES = 100; // 页面结构变化或游标异常时，最多抓取到此页数。
    private static final int METADATA_BATCH_SIZE = 25; // 每批元数据查询不超过源站接口允许的条数。
    private static final int VISUAL_SAMPLE_COUNT = 16;
    private static final long MAX_PREVIEW_BYTES = 10L * 1024 * 1024;

    /** 为每个画廊均匀采样少量预览图生成指纹，单个画廊失败不会阻断其他画廊。 */
    @Override
    public Map<Long, List<GalleryPageFingerprint>> analyzeGalleryPreviews(List<EhGalleriesEntity> galleries) {
        Map<Long, List<GalleryPageFingerprint>> result = new LinkedHashMap<>();
        if (galleries == null) return result;
        int processed = 0;
        for (EhGalleriesEntity gallery : galleries) {
            if (gallery == null || gallery.getGid() == null || StrUtil.isBlank(gallery.getToken())) continue;
            Activity.getExecutionContext().heartbeat("视觉预览 " + (++processed) + "/" + galleries.size());
            String galleryUrl = StrUtil.blankToDefault(gallery.getGalleryUrl(),
                    Constants.EHENTAI_BASE_URL + "g/" + gallery.getGid() + "/" + gallery.getToken() + "/");
            try {
                Document document = Jsoup.parse(ehNetworkClient.getHtml(galleryUrl), galleryUrl);
                Elements images = document.select("#gdt img, .gdtm img, .gdtl img");
                List<Element> sampled = sampleElements(images, VISUAL_SAMPLE_COUNT);
                List<GalleryPageFingerprint> fingerprints = new ArrayList<>();
                for (int index = 0; index < sampled.size(); index++) {
                    Element image = sampled.get(index);
                    String imageUrl = image.hasAttr("data-src") ? image.absUrl("data-src") : image.absUrl("src");
                    if (StrUtil.isBlank(imageUrl)) {
                        imageUrl = image.hasAttr("data-src") ? image.attr("data-src") : image.attr("src");
                    }
                    if (StrUtil.isBlank(imageUrl) || imageUrl.startsWith("data:")) continue;
                    try {
                        byte[] bytes = ehNetworkClient.getBytes(imageUrl, MAX_PREVIEW_BYTES);
                        GalleryPageFingerprint fingerprint = PerceptualHash.fingerprint(
                                new ByteArrayInputStream(bytes), gallery.getGid(), index,
                                "preview-" + index, "PREVIEW");
                        if (fingerprint != null) fingerprints.add(fingerprint);
                    } catch (Exception imageFailure) {
                        log.debug("预览图指纹失败, GID: {}, URL: {}, 原因: {}",
                                gallery.getGid(), imageUrl, imageFailure.getMessage());
                    }
                }
                result.put(gallery.getGid(), fingerprints);
                log.info("🖼️ GID {} 已生成 {}/{} 个预览指纹", gallery.getGid(), fingerprints.size(), sampled.size());
            } catch (Exception failure) {
                log.warn("画廊预览分析失败，不阻断普通抓取, GID: {}, 原因: {}", gallery.getGid(), failure.getMessage());
                result.put(gallery.getGid(), List.of());
            }
        }
        return result;
    }

    /** 在图片列表首尾之间均匀取样，避免只比较封面附近的页面。 */
    private List<Element> sampleElements(Elements images, int limit) {
        if (images == null || images.isEmpty()) return List.of();
        if (images.size() <= limit) return new ArrayList<>(images);
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        for (int i = 0; i < limit; i++) {
            indexes.add((int) Math.round(i * (images.size() - 1D) / (limit - 1D)));
        }
        return indexes.stream().map(images::get).toList();
    }

    @Override
    /** 持续抓取检索页并汇总记录，停止后补齐作品识别需要的元数据。 */
    public List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions) {
        List<EhGalleriesEntity> allResults = new ArrayList<>();
        String currentUrl = buildInitialSearchUrl(searchOptions);
        int pageNo = 1;
        String lastNextCursor = currentUrl;

        // 每次循环只处理一页，记录心跳后再判断是否继续，防止长任务被误判超时。
        while (StrUtil.isNotBlank(currentUrl) && pageNo <= MAX_SAFE_PAGES) {
            // 汇报当前页码，任务引擎可据此判断抓取仍在进行。
            Activity.getExecutionContext().heartbeat(pageNo);
            log.info("正在抓取第 {} 页: {}", pageNo, currentUrl);

            // 抓取单页数据
            PageScrapeResult pageResult = scrapeSinglePage(currentUrl, searchOptions, pageNo);
            allResults.addAll(pageResult.getGalleries());

            // 无结果或没有下一页时停止，并给本次记录保留停止原因。
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

            // 翻页之间留出间隔，减少连续请求给源站带来的压力。
            ThreadUtil.sleep(DELAY_MS);
        }

        // 达到上限也保留明确停止原因，便于排查分页结构或筛选范围。
        if (pageNo > MAX_SAFE_PAGES) {
            log.warn("达到最大安全翻页限制 {}，停止抓取，防止死循环。", MAX_SAFE_PAGES);
            allResults.forEach(r -> r.setTraceStopReason("max_safe_pages_reached"));
        }

        // 保存最后一次下一页地址，方便追踪任务停在何处。
        String finalCursor = lastNextCursor;
        allResults.forEach(r -> r.setTraceLastNextCursor(finalCursor));

        enrichGalleryMetadata(allResults);
        log.info("抓取完成，共提取到 {} 个有效画廊。", allResults.size());
        return allResults;
    }

    /**
     * 每次只返回一页结果，避免一次把全部画廊放入任务响应而超过载荷大小限制。
     * 后续翻页、汇总和入库由工作流安排，本方法同时补齐当前页的元数据。
     */
    @Override
    public GalleryScrapePage scrapeGalleryPage(SearchOptions searchOptions, String currentUrl, int pageNo) {
        String url = StrUtil.blankToDefault(currentUrl, buildInitialSearchUrl(searchOptions));
        Activity.getExecutionContext().heartbeat(pageNo);
        log.info("正在抓取第 {} 页: {}", pageNo, url);

        PageScrapeResult pageResult = scrapeSinglePage(url, searchOptions, pageNo);
        List<EhGalleriesEntity> galleries = pageResult.getGalleries();
        enrichGalleryMetadata(galleries);

        GalleryScrapePage response = new GalleryScrapePage();
        response.setGalleries(galleries);
        response.setHasData(pageResult.isHasData());
        response.setHasNextPage(pageResult.isHasNextPage());
        response.setNextUrl(pageResult.getNextUrl());
        String stopReason = !pageResult.isHasData() ? "no_data_on_page"
                : (!pageResult.isHasNextPage() ? "end_of_pages" : null);
        response.setStopReason(stopReason);
        galleries.forEach(gallery -> {
            gallery.setTraceLastNextCursor(pageResult.getNextUrl());
            if (stopReason != null) gallery.setTraceStopReason(stopReason);
        });
        return response;
    }

    @Override
    /** 从完整链接或 GID/token 输入构造记录，补齐元数据后丢弃仍无法识别标题的项。 */
    public List<EhGalleriesEntity> scrapeGalleriesByUrls(List<String> galleryUrls) {
        List<EhGalleriesEntity> galleries = new ArrayList<>();
        if (galleryUrls == null) return galleries;
        for (String reference : new LinkedHashSet<>(galleryUrls)) {
            if (StrUtil.isBlank(reference)) continue;
            Matcher matcher = GALLERY_REFERENCE_PATTERN.matcher(StrUtil.trim(reference));
            if (!matcher.matches()) {
                log.warn("无效画廊链接或 GID/token 格式，跳过: {}", reference);
                continue;
            }
            long gid = Long.parseLong(matcher.group(1));
            String token = matcher.group(2);
            EhGalleriesEntity gallery = new EhGalleriesEntity();
            gallery.setGid(gid);
            gallery.setToken(token);
            gallery.setGalleryUrl(String.format("%sg/%d/%s/", Constants.EHENTAI_BASE_URL, gid, token));
            gallery.setSearchQuery("gid:" + gid);
            gallery.setCrawledAt(new Date());
            gallery.setDownloadStatus(STATUS_PENDING);
            galleries.add(gallery);
        }
        enrichGalleryMetadata(galleries);
        galleries.removeIf(gallery -> StrUtil.isBlank(gallery.getTitle()));
        galleries.forEach(gallery -> gallery.setFilename(gallery.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_")));
        log.info("按画廊链接抓取完成：请求 {} 个，成功解析 {} 个", galleryUrls.size(), galleries.size());
        return galleries;
    }

    /**
     * 按源站接口的单批上限补齐标题、标签、页数和评分，再生成作品识别信息。
     * 某批查询失败时保留普通抓取结果，不让元数据暂不可用阻断整个采集任务。
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
                    String title = StrUtil.blankToDefault(metadata.getStr("title"), metadata.getStr("title_jpn"));
                    if (StrUtil.isNotBlank(title)) {
                        gallery.setTitle(title);
                    }
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

    /** 读取可选整数；源站缺值或格式异常时保留为空，避免误当成有效的零。 */
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

    /** 读取可选小数；无法解析时保留为空，供后续判断信息是否缺失。 */
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
     * 将关键词、语言、分类及高级筛选拼成源站可接受的初始检索地址。
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
     * 解析一页中的画廊标题、链接和身份信息，并提取不同于当前页的下一页地址。
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
    /** 先读取归档页面估算大小，再提交下载表单，解析跳转地址并补齐启动参数。 */
    public ArchiveDownloadInfo extractDownloadUrl(Long gid, String token) {
        String archiveUrl = String.format("%s?gid=%d&token=%s", Constants.EHENTAI_ARCHIVER_URL, gid, token);
        ArchiveDownloadInfo info = new ArchiveDownloadInfo();

        // 两次串行网络请求之间汇报阶段进度，让任务引擎知道当前仍在提取下载地址。
        Activity.getExecutionContext().heartbeat("正在获取归档页面元数据: " + gid);

        // 第一步读取归档准备页，优先从原始归档下载区域取得文件大小。
        log.info("正在获取归档页面元数据: {}", archiveUrl);
        String prepareHtml = ehNetworkClient.getHtml(archiveUrl);
        Document doc = Jsoup.parse(prepareHtml);

        double finalSizeMb = 0.0;

        // 优先读取明确的原始归档区域，减少误取其他页面数字的机会。
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

        // 指定区域没有大小时，改在整页查找预计大小文字。
        if (finalSizeMb <= 0.0) {
            log.info("⚠️ H@H 区域未能获取到大小，回退到全文匹配模式...");

            // 兼容普通空格、HTML 实体 (&nbsp;) 和可能穿插的 <strong> 标签
            String sizeRegex = "Estimated Size:[\\s\\u00A0]*(<[^>]+>)*[\\s\\u00A0]*([\\d.]+)[\\s\\u00A0]*(MB|GB|KB|MiB|GiB)";
            Matcher fallbackMatcher = Pattern.compile(sizeRegex, Pattern.CASE_INSENSITIVE).matcher(prepareHtml);

            if (fallbackMatcher.find()) {
                finalSizeMb = parseSizeToMb(fallbackMatcher.group(2), fallbackMatcher.group(3));
                log.info("📊 从全文回退模式解析到大小: {} MB", finalSizeMb);
            } else {
                // 页面标签干扰匹配时，最后改为读取去掉标签的纯文本。
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

        // 第二步提交原始归档下载表单，取得最终的下载跳转地址。
        Map<String, Object> form = new HashMap<>();
        form.put("dlcheck", "Download Original Archive");
        form.put("dltype", "org");

        String postHtml = ehNetworkClient.postForm(archiveUrl, form);
        Document postDoc = Jsoup.parse(postHtml);

        // 优先解析脚本中的页面跳转；没有脚本跳转时再检查普通下载链接。
        String jsRedirect = ReUtil.getGroup1("document\\.location\\s*=\\s*['\"](https?://[^'\"]+)['\"]", postHtml);
        // 直接解析下载按钮链接，兼容没有脚本跳转的页面。
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
     * 将页面上不同单位的文件大小统一换算为 MB。
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
     * 保存单页解析结果，包括画廊记录、是否有数据以及下一页地址。
     */
    @Data
    private static class PageScrapeResult {
        private List<EhGalleriesEntity> galleries = new ArrayList<>();
        private boolean hasData = false;
        private boolean hasNextPage = false;
        private String nextUrl;
    }
}
