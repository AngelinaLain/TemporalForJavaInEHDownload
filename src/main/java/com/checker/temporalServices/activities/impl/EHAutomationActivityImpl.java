package com.checker.temporalServices.activities.impl;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.EhNetworkClient;
import com.checker.config.EhNetworkConfig;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.temporalServices.activities.EHAutomationActivity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ActivityImpl(taskQueues = "EHDownloadTaskQueue")
public class EHAutomationActivityImpl implements EHAutomationActivity {

    @Autowired
    private EhNetworkClient ehNetworkClient;

    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private EhNetworkConfig netConfig;

    private static final String STATUS_PENDING = "未下载";

    @Override
    public List<EhGalleriesEntity> scrapeGalleries(SearchOptions searchOptions) {
        List<EhGalleriesEntity> results = new ArrayList<>();
        // 1. 初始链接拼接 (根据 params.md 要求)
        String baseUrl = "https://e-hentai.org/";
        
        // 构建 f_search 参数：keyword + language
        String searchParam = URLUtil.encodeAll(searchOptions.getKeyword());
        if (StrUtil.isNotBlank(searchOptions.getLanguage())) {
            // 追加语言条件：语言用空格分隔，URL编码
            searchParam += "%20language%3A%22" + URLUtil.encodeAll(searchOptions.getLanguage()) + "%22";  
        }
        
        String currentUrl = String.format("%s?f_search=%s&f_cats=%d&advsearch=1",
                baseUrl, searchParam, searchOptions.getFCats());
        
        // 添加星级过滤 (f_srdd)
        if (searchOptions.getMinimumRating() != null && searchOptions.getMinimumRating() > 1) {
            currentUrl += "&f_srdd=" + searchOptions.getMinimumRating();
        }
        
        // 添加页数范围过滤
        if (searchOptions.getPageAtLeast() != null && searchOptions.getPageAtLeast() > 0) {
            currentUrl += "&f_spf=" + searchOptions.getPageAtLeast();  // 最少页数
        }
        if (searchOptions.getPageAtMost() != null && searchOptions.getPageAtMost() > 0) {
            currentUrl += "&f_spt=" + searchOptions.getPageAtMost();   // 最多页数
        }
        
        // 添加高级搜索选项
        if (searchOptions.getSearchExpungedGalleries() != null && searchOptions.getSearchExpungedGalleries()) {
            currentUrl += "&f_sh=on";  // 搜索已删除的画廊
        }
        if (searchOptions.getShowOnlyWithTorrents() != null && searchOptions.getShowOnlyWithTorrents()) {
            currentUrl += "&f_sto=on"; // 仅显示有种子的
        }
        
        // 添加禁用过滤器选项
        if (searchOptions.getDisableLanguageFilter() != null && searchOptions.getDisableLanguageFilter()) {
            currentUrl += "&f_sfl=on"; // 禁用语言过滤
        }
        if (searchOptions.getDisableUploaderFilter() != null && searchOptions.getDisableUploaderFilter()) {
            currentUrl += "&f_sfu=on"; // 禁用上传者过滤
        }
        if (searchOptions.getDisableTagsFilter() != null && searchOptions.getDisableTagsFilter()) {
            currentUrl += "&f_sft=on"; // 禁用标签过滤
        }

        int maxPages = 10;   // params.md: 最大抓取页数
        int delayMs = 3000;  // params.md: 翻页延迟(防封禁)
        String lastNextCursor = null;

        for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
            log.info("正在抓取第 {} 页: {}", pageNo, currentUrl);

            // 2. 调用刚才封装的网络客户端获取 HTML（自带代理、Cookie 和 509 校验）
            String html = ehNetworkClient.getHtml(currentUrl);

            // 3. 正则解析当前页的画廊列表
            // EHentai 典型画廊区块: <a href="https://e-hentai.org/g/12345/abcde/"><div class="glink">画廊标题</div></a>
            String regex = "<a href=\"(https://e-hentai\\.org/g/(\\d+)/([a-z0-9]+)/)\"[^>]*><div class=\"glink\">([^<]+)</div>";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(html);

            boolean hasData = false;
            while (matcher.find()) {
                hasData = true;
                EhGalleriesEntity entity = new EhGalleriesEntity();

                // 提取正则匹配到的分组
                entity.setGalleryUrl(matcher.group(1));
                entity.setGid(Long.parseLong(matcher.group(2)));
                entity.setToken(matcher.group(3));
                entity.setTitle(matcher.group(4));

                // 4. 清洗出安全的文件名 (替换 Windows 文件名不允许的字符: \ / : * ? " < > |)
                String safeFilename = entity.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
                entity.setFilename(safeFilename);

                // 5. 补充其他基础字段
                entity.setSearchQuery(searchOptions.getKeyword());
                entity.setCrawledAt(new Date());
                entity.setDownloadStatus(STATUS_PENDING);
                entity.setTracePagesCrawled(pageNo);
                entity.setTraceFirstPageTitle(ReUtil.getGroup1("<title>(.*?)</title>", html));

                results.add(entity);
            }

            if (!hasData) {
                log.info("当前页未解析到画廊数据，可能已到底部。");
                for (EhGalleriesEntity row : results) {
                    row.setTraceStopReason("no_data_on_page");
                }
                break;
            }

            // 6. 解析下一页游标 (Next Cursor)
            // 典型 HTML: <a id="dnext" class="ptds" href="https://e-hentai.org/?next=123456789&f_search=...">
            String nextUrlRegex = "href=\"([^\"]+)\"[^>]*id=\"dnext\"";
            String nextUrl = ReUtil.getGroup1(nextUrlRegex, html);

            if (nextUrl == null) {
                log.info("未找到下一页游标 (no_next_cursor)，停止翻页。");
                for (EhGalleriesEntity row : results) {
                    row.setTraceStopReason("no_next_cursor");
                }
                break;
            }

            // 还原 HTML 实体转义符
            nextUrl = nextUrl.replace("&amp;", "&");

            // 防死循环校验：如果 next 没变，就停下
            if (nextUrl.equals(currentUrl)) {
                log.info("下一页游标未发生变化 (next_cursor_not_changed)，停止翻页。");
                for (EhGalleriesEntity row : results) {
                    row.setTraceStopReason("next_cursor_not_changed");
                }
                break;
            }

            currentUrl = nextUrl;
            lastNextCursor = currentUrl;

            // 7. 翻页防封禁延迟 (强制休眠 delayMs)
            if (pageNo < maxPages) {
                ThreadUtil.sleep(delayMs);
            }
        }

        for (EhGalleriesEntity row : results) {
            row.setTraceLastNextCursor(lastNextCursor);
        }

        log.info("抓取完成，共提取到 {} 个有效画廊。", results.size());
        return results;
    }

    @Override
    public void saveToDatabase(EhGalleriesEntity gallery) {
        // 为了保证 Temporal 重试时的幂等性，先根据主键 (GID) 查一下是否存在
        EhGalleriesEntity existing = galleriesMapper.selectById(gallery.getGid());

        if (existing == null) {
            // 不存在，执行插入
            galleriesMapper.insert(gallery);
            log.info("✅ 新画廊已入库, GID: {}, 标题: {}", gallery.getGid(), gallery.getTitle());
        } else {
            // 已存在，执行更新（这在补充抓取或者断点续传时非常有用）
            galleriesMapper.updateById(gallery);
            log.info("🔄 画廊信息已更新, GID: {}", gallery.getGid());
        }
    }

    @Override
    public String extractDownloadUrl(Long gid, String token) {
        String archiveUrl = String.format("https://e-hentai.org/archiver.php?gid=%d&token=%s", gid, token);
        Map<String, Object> form = new HashMap<>();
        form.put("dlcheck", "Download Original Archive");
        form.put("dltype", "org");

        String html = ehNetworkClient.postForm(archiveUrl, form);
        String jsRedirect = ReUtil.getGroup1("document\\.location\\s*=\\s*['\"](https?://[^'\"]+)['\"]", html);
        String clickLink = ReUtil.getGroup1("<a href=\"([^\"]+)\">Click Here To Start Downloading</a>", html);

        String finalUrl = null;
        if (StrUtil.isNotBlank(jsRedirect)) {
            finalUrl = jsRedirect;
        } else if (StrUtil.isNotBlank(clickLink)) {
            finalUrl = clickLink.startsWith("http") ? clickLink : "https://e-hentai.org" + clickLink;
        }

        if (StrUtil.isBlank(finalUrl)) {
            throw ApplicationFailure.newFailure(
                    "无法提取下载链接，可能 GP 不足或配额受限",
                    "ARCHIVE_LINK_EXTRACT_FAILED"
            );
        }

        String safeFinalUrl = finalUrl;
        if (!safeFinalUrl.contains("start=1")) {
            safeFinalUrl = safeFinalUrl + (safeFinalUrl.contains("?") ? "&" : "?") + "start=1";
        }

        return safeFinalUrl;
    }

    @Override
    public Long pushToSynology(String downloadUrl, Long gid, String destination) {
        String sid = getSynologySid();
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "3");
        form.put("method", "create");
        form.put("_sid", sid);
        form.put("uri", downloadUrl);
        form.put("destination", StrUtil.blankToDefault(destination, netConfig.getSynology().getDestination()));

        String taskApi = netConfig.getSynology().getUrl() + "/webapi/DownloadStation/task.cgi";
        String response = postSynologyForm(taskApi, form);

        // Synology create API 返回 {"data":{},"success":true} 不含 task_id
        // 我们改为依赖 GID + URL 在后续 list 查询时精确匹配任务
        if (response.contains("\"success\":true")) {
            log.info("✅ 任务已推送到群晖, GID: {}, URI: {}", gid, downloadUrl);
            return gid;  // 返回 GID，用于后续任务追踪
        } else {
            throw ApplicationFailure.newFailure(
                    "群晖任务创建失败: " + response,
                    "SYNOLOGY_CREATE_FAILED"
            );
        }
    }

    /**
     * 通过 GID + downloadUrl 的 URI 匹配来查询群晖下载任务状态
     */
    @Override
    public String checkSynologyTaskStatus(Long gid, String downloadUrl) {
        String sid = getSynologySid();

        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.DownloadStation.Task");
        form.put("version", "1");
        form.put("method", "list");
        // 【关键修复】必须加这个参数，群晖才会在返回体中包含 uri 信息
        form.put("additional", "detail");
        form.put("_sid", sid);

        String taskApi = netConfig.getSynology().getUrl() + "/webapi/DownloadStation/task.cgi";
        String response = postSynologyForm(taskApi, form);

        cn.hutool.json.JSONObject jsonObj = cn.hutool.json.JSONUtil.parseObj(response);
        if (!jsonObj.getBool("success", false)) {
            log.warn("❌ 群晖 list 接口调用失败, GID: {}", gid);
            return "error";
        }

        cn.hutool.json.JSONArray tasks = jsonObj.getByPath("data.tasks", cn.hutool.json.JSONArray.class);
        if (tasks == null || tasks.isEmpty()) {
            log.warn("⚠️ 任务列表为空, GID: {} (可能已被删除)", gid);
            return "finished"; // 业务兜底
        }

        // 遍历任务列表，寻找匹配的 URI
        for (int i = 0; i < tasks.size(); i++) {
            cn.hutool.json.JSONObject task = tasks.getJSONObject(i);
            // 从 additional.detail.uri 中获取链接
            String taskUri = task.getByPath("additional.detail.uri", String.class);

            if (StrUtil.isNotBlank(taskUri) && taskUri.equals(downloadUrl)) {
                // 【关键修复】获取真正的 String 状态
                String status = task.getStr("status", "").toLowerCase();

                log.info("🔍 找到匹配任务, GID: {}, status: {}", gid, status);

                if ("finished".equals(status) || "seeding".equals(status) || "extracted".equals(status)) {
                    return "finished";
                } else if ("error".equals(status) || "broken".equals(status) || "file_not_found".equals(status)) {
                    log.warn("❌ 任务异常, GID: {}", gid);
                    return "error";
                } else {
                    return "downloading";
                }
            }
        }

        log.warn("⚠️ 任务不在列表中, GID: {}, URI: {} (可能已完成并被清除)", gid, downloadUrl);
        return "finished";
    }

    @Override
    public void updateGalleryStatus(Long gid, String status) {
        // 【MyBatis-Plus 核心技巧】
        // 我们不需要查出整条数据再更新。只需 new 一个实体，塞入主键和要修改的值。
        // MP 会自动生成动态 SQL，忽略掉所有 null 的字段，只更新 status。
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setDownloadStatus(status);

        galleriesMapper.updateById(updateEntity);
        log.info("📝 状态更新完成, GID: {}, 新状态值: {}", gid, status);
    }

    @Override
    public void sendEmailAlert(String subject, String content) {
        String adminEmail = netConfig.getNotification().getAdminEmail();
        if (StrUtil.isBlank(adminEmail)) {
            log.warn("未配置通知邮箱，跳过邮件发送。主题: {}, 内容: {}", subject, content);
            return;
        }
        log.info("邮件通知占位: to={}, subject={}, content={}", adminEmail, subject, content);
    }

    @Override
    public List<EhGalleriesEntity> getFailedGalleries() {
        // 使用 MyBatis-Plus 查询所有下载失败的画廊
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("download_status", "下载失败");
        return galleriesMapper.selectList(queryWrapper);
    }

    /**
     * 获取群辉的Sid
     * @return 返回sid
     */
    private String getSynologySid() {
        // 1. URL 只保留最基础的路径
        String authUrl = netConfig.getSynology().getUrl() + "/webapi/auth.cgi";

        // 2. 将所有参数放入 Map 表单中
        Map<String, Object> form = new HashMap<>();
        form.put("api", "SYNO.API.Auth");
        form.put("version", "3"); // 你的日志里显示改成了 3，群晖 DSM 6/7 一般 3 到 7 都支持
        form.put("method", "login");
        form.put("session", "DownloadStation");
        form.put("format", "sid");
        // 注意：放进 Map 里不需要手动 URLUtil.encode，Hutool 会自动处理！
        form.put("account", netConfig.getSynology().getUsername());
        form.put("passwd", netConfig.getSynology().getPassword());

        // 3. 发送带有完整表单体的 POST 请求
        String resp = postSynologyForm(authUrl, form);

        // 4. 解析返回值
        cn.hutool.json.JSONObject jsonObj = cn.hutool.json.JSONUtil.parseObj(resp);
        if (jsonObj.getBool("success", false)) {
            return jsonObj.getByPath("data.sid", String.class);
        } else {
            throw ApplicationFailure.newFailure("登录群晖失败: " + resp, "SYNOLOGY_AUTH_FAILED");
        }
    }

    private String postSynologyForm(String url, Map<String, Object> form) {
        HttpRequest req = HttpRequest.post(url)
                .timeout(20000)
                .setFollowRedirects(true)
                .setHostnameVerifier((hostname, session) -> true);

        if (form != null && !form.isEmpty()) {
            req.form(form);
        }

        try (HttpResponse response = req.execute()) {
            if (!response.isOk()) {
                throw ApplicationFailure.newFailure(
                        "群晖接口调用失败: HTTP " + response.getStatus(),
                        "SYNOLOGY_API_ERROR"
                );
            }
            return response.body();
        }
    }
}
