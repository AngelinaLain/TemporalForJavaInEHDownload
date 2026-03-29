package com.checker.temporalServices.activities.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.extra.ssh.JschUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.checker.common.EhNetworkClient;
import com.checker.config.EhNetworkConfig;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.temporalServices.activities.EHAutomationActivity;
import com.jcraft.jsch.Session;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
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
            // EHentai 典型画廊区块:
            // <a href="https://e-hentai.org/g/12345/abcde/">
            //  <div class="glink">画廊标题</div></a>
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
            // 典型 HTML: <a id="unext" href="https://e-hentai.org/?next=3859750">下一页 ></a>
            String nextUrlRegex = "href=\"([^\"]+)\"[^>]*id=\"unext\"";
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

        JSONObject jsonObj = JSONUtil.parseObj(response);
        if (!jsonObj.getBool("success", false)) {
            log.warn("❌ 群晖 list 接口调用失败, GID: {}", gid);
            return "error";
        }

        JSONArray tasks = jsonObj.getByPath("data.tasks", JSONArray.class);
        if (tasks == null || tasks.isEmpty()) {
            log.warn("⚠️ 任务列表为空, GID: {} (可能已被删除)", gid);
            return "finished"; // 业务兜底
        }

        // 遍历任务列表，寻找匹配的 URI
        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            // 从 additional.detail.uri 中获取链接
            String taskUri = task.getByPath("additional.detail.uri", String.class);

            if (StrUtil.isNotBlank(taskUri) && taskUri.equals(downloadUrl)) {
                // 【关键修复】获取真正的 String 状态
                String status = task.getStr("status", "").toLowerCase();

                log.info("🔍 找到匹配任务, GID: {}, status: {}", gid, status);

                if ("finished".equals(status) || "seeding".equals(status) || "extracted".equals(status)) {
                    String taskTitle = task.getStr("title", "");
                    if (StrUtil.isNotBlank(taskTitle)) {
                        EhGalleriesEntity updateFile = new EhGalleriesEntity();
                        updateFile.setGid(gid);
                        // 不再剔除后缀名，直接保存群晖物理磁盘上的完整文件名 (含 .zip)
                        updateFile.setFilename(taskTitle);
                        galleriesMapper.updateById(updateFile);
                        log.info("💾 已记录群晖真实完整文件名: {}", taskTitle);
                    }
                    return "finished";
                }else if ("error".equals(status) || "broken".equals(status) || "file_not_found".equals(status)) {
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
        EhNetworkConfig.Notification notifConfig = netConfig.getNotification();
        if (StrUtil.isBlank(notifConfig.getAdminEmail()) || StrUtil.isBlank(notifConfig.getTenantId())) {
            log.warn("未配置完整的邮件通知参数，跳过邮件发送。主题: {}", subject);
            return;
        }
        try {
            // 1. 获取 Microsoft Graph Access Token
            String tokenUrl = String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token", notifConfig.getTenantId());
            Map<String, Object> tokenForm = new HashMap<>();
            tokenForm.put("client_id", notifConfig.getClientId());
            tokenForm.put("client_secret", notifConfig.getClientSecret());
            tokenForm.put("scope", "https://graph.microsoft.com/.default");
            tokenForm.put("grant_type", "client_credentials");
            String tokenResp = HttpRequest.post(tokenUrl)
                    .form(tokenForm)
                    .timeout(10000)
                    .execute().body();
            JSONObject tokenJson = JSONUtil.parseObj(tokenResp);
            String accessToken = tokenJson.getStr("access_token");
            if (StrUtil.isBlank(accessToken)) {
                log.error("获取 Microsoft Graph Token 失败: {}", tokenResp);
                return;
            }
            // 2. 组装 Graph API 发送邮件的 JSON Body
            JSONObject mailPayload = getEntries(subject, content, notifConfig);
            // 3. 调用 Graph API 发送邮件
            String sendMailUrl = String.format("https://graph.microsoft.com/v1.0/users/%s/sendMail", notifConfig.getSenderEmail());
            try (HttpResponse mailResp = HttpRequest.post(sendMailUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .body(mailPayload.toString())
                    .timeout(15000)
                    .execute()) {
                if (mailResp.isOk() || mailResp.getStatus() == 202) {
                    log.info("✅ Graph API 邮件通知已成功发送至: {}", notifConfig.getAdminEmail());
                } else {
                    log.error("❌ Graph API 发送邮件失败, HTTP状态码: {}, 响应: {}", mailResp.getStatus(), mailResp.body());
                }
            }
        } catch (Exception e) {
            log.error("❌ 邮件通知流程发生异常: {}", e.getMessage());
        }
    }

    @NotNull
    private static JSONObject getEntries(String subject, String content, EhNetworkConfig.Notification notifConfig) {
        JSONObject message = new JSONObject();
        message.set("subject", subject);

        JSONObject fromEmailAddress = new JSONObject();
        fromEmailAddress.set("address", notifConfig.getSenderEmail());
        fromEmailAddress.set("name", "EHentai 自动化机器人");
        JSONObject from = new JSONObject();
        from.set("emailAddress", fromEmailAddress);
        message.set("from", from);

        // 获取当前时间 (依赖 Hutool)
        String currentTime = DateUtil.now();

        // 将正文中的普通换行符替换为 HTML 的换行标签，防止挤在一坨
        String htmlSafeContent = content.replace("\n", "<br>");

        // 组装精美的 HTML 模板
        String htmlTemplate = String.format(
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head>" +
                        "<style>" +
                        "  body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f9f9f9; color: #333; margin: 0; padding: 20px; }" +
                        "  .card { background-color: #ffffff; border: 1px solid #e0e0e0; border-radius: 8px; padding: 24px; max-width: 600px; margin: 0 auto; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }" +
                        "  .header { border-bottom: 2px solid #0078D4; padding-bottom: 12px; margin-bottom: 20px; font-size: 20px; font-weight: bold; color: #0078D4; }" +
                        "  .content { font-size: 15px; line-height: 1.6; }" +
                        "  .footer { margin-top: 30px; padding-top: 15px; border-top: 1px dashed #ccc; font-size: 12px; color: #888; }" +
                        "</style>" +
                        "</head>" +
                        "<body>" +
                        "  <div class='card'>" +
                        "    <div class='header'>%s</div>" +
                        "    <div class='content'>%s</div>" +
                        "    <div class='footer'>" +
                        "      <strong>触发时间：</strong> %s <br>" +
                        "      <strong>系统来源：</strong> EHentai 自动化工作流 (Temporal)" +
                        "    </div>" +
                        "  </div>" +
                        "</body>" +
                        "</html>",
                subject, htmlSafeContent, currentTime
        );

        JSONObject body = new JSONObject();
        // 🚀 核心修改：将 Text 改为 HTML
        body.set("contentType", "HTML");
        body.set("content", htmlTemplate);
        message.set("body", body);

        JSONArray toRecipients = new JSONArray();
        JSONObject emailAddress = new JSONObject();
        emailAddress.set("address", notifConfig.getAdminEmail());
        JSONObject recipient = new JSONObject();
        recipient.set("emailAddress", emailAddress);
        toRecipients.add(recipient);
        message.set("toRecipients", toRecipients);

        JSONObject mailPayload = new JSONObject();
        mailPayload.set("message", message);
        mailPayload.set("saveToSentItems", "false");

        return mailPayload;
    }

    @Override
    public List<EhGalleriesEntity> getFailedGalleries() {
        // 使用 MyBatis-Plus 查询所有下载失败的画廊
        QueryWrapper<EhGalleriesEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("download_status", "下载失败", "已下载");
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
        JSONObject jsonObj = JSONUtil.parseObj(resp);
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

    @Override
    public EhGalleriesEntity getGalleryById(Long gid) {
        return galleriesMapper.selectById(gid);
    }

    // 1. 获取并保存元数据
    @Override
    public void fetchAndSaveMetadata(Long gid, String token) {
        // 调用 EHentai 的 gdata API
        String apiUrl = "https://api.e-hentai.org/api.php";
        String jsonBody = String.format("{\"method\":\"gdata\",\"gidlist\":[[%d,\"%s\"]],\"namespace\":1}", gid, token);

        String response = HttpRequest.post(apiUrl)
                .body(jsonBody)
                .execute().body();

        // 解析返回的 JSON，提取 tags
        JSONObject resObj = JSONUtil.parseObj(response);
        JSONArray gmetadata = resObj.getJSONArray("gmetadata");
        if (gmetadata != null && !gmetadata.isEmpty()) {
            JSONArray tagsArray = gmetadata.getJSONObject(0).getJSONArray("tags");
            List<String> tagsList = tagsArray.toList(String.class);
            // 将获取到的 Tags 更新到数据库
            EhGalleriesEntity updateEntity = new EhGalleriesEntity();
            updateEntity.setGid(gid);
            updateEntity.setTags(tagsList);
            galleriesMapper.updateById(updateEntity);
            log.info("✅ GID: {} 元数据获取并存库成功，共 {} 个标签", gid, tagsList.size());
        }
    }
    @Override
    public String findBookInKomga(Long gid) {
        // ==========================================
        // 🚀 第一步：动态获取 N8N_Update 的 SeriesID
        // ==========================================
        String seriesName = "N8N_Update"; // 你的目标系列/文件夹名称
        String targetSeriesId = getSeriesIdByName(seriesName);

        if (StrUtil.isBlank(targetSeriesId)) {
            log.error("❌ 无法在 Komga 中找到名为 [{}] 的系列", seriesName);
            return null;
        }

        // ==========================================
        // 🚀 第二步：使用 SeriesID + GID 联合精确检索 BookID
        // ==========================================
        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/list";

        cn.hutool.json.JSONObject searchBody = new cn.hutool.json.JSONObject();
        searchBody.set("fullTextSearch", String.valueOf(gid));

        // 完美复刻你抓包得到的 condition 结构
        cn.hutool.json.JSONObject condition = new cn.hutool.json.JSONObject();
        cn.hutool.json.JSONObject seriesIdCond = new cn.hutool.json.JSONObject();
        seriesIdCond.set("operator", "is");
        seriesIdCond.set("value", targetSeriesId);
        condition.set("seriesId", seriesIdCond);

        searchBody.set("condition", condition);

        HttpResponse response = HttpRequest.post(komgaUrl)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute();

        if (response.isOk()) {
            cn.hutool.json.JSONObject resObj = cn.hutool.json.JSONUtil.parseObj(response.body());
            cn.hutool.json.JSONArray content = resObj.getJSONArray("content");

            if (content != null && !content.isEmpty()) {
                String bookId = content.getJSONObject(0).getStr("id");
                log.info("🎯 Komga 复合检索精准命中! GID: {}, 锁定系列: {}, BookID: {}",
                        gid, seriesName, bookId);
                return bookId; // 返回精准无误的 BookID 交给 HttpClient 去 PATCH！
            } else {
                log.warn("⚠️ 在系列 [{}] 中未找到包含 GID: {} 的书籍", seriesName, gid);
            }
        } else {
            log.error("❌ Komga 复合搜索失败: HTTP {}, {}", response.getStatus(), response.body());
        }
        return null;
    }

    /**
     * 辅助方法：通过系列名称动态获取 SeriesID，避免硬编码
     */
    private String getSeriesIdByName(String seriesName) {
        String url = netConfig.getKomga().getUrl() + "/api/v1/series/list?size=50";

        cn.hutool.json.JSONObject searchBody = new cn.hutool.json.JSONObject();
        searchBody.set("fullTextSearch", seriesName);

        HttpResponse response = HttpRequest.post(url)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute();

        if (!response.isOk()) {
            log.error("❌ Komga series/list 查询失败: HTTP {}, {}", response.getStatus(), response.body());
            return null;
        }

        cn.hutool.json.JSONObject resObj = cn.hutool.json.JSONUtil.parseObj(response.body());
        cn.hutool.json.JSONArray content = resObj.getJSONArray("content");
        if (content == null || content.isEmpty()) return null;

        // 精确匹配优先：先匹配 metadata.title，再匹配 name
        for (int i = 0; i < content.size(); i++) {
            cn.hutool.json.JSONObject series = content.getJSONObject(i);
            String title = series.getByPath("metadata.title", String.class);
            if (seriesName.equalsIgnoreCase(StrUtil.blankToDefault(title, ""))) {
                return series.getStr("id");
            }
        }
        for (int i = 0; i < content.size(); i++) {
            cn.hutool.json.JSONObject series = content.getJSONObject(i);
            if (seriesName.equalsIgnoreCase(series.getStr("name"))) {
                return series.getStr("id");
            }
        }

        // 兜底返回第一条
        return content.getJSONObject(0).getStr("id");
    }

    // 3. 将元数据和完美标题推送给 Komga
    @Override
    public void pushMetadataToKomga(String bookId, Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        JSONObject metadata = new JSONObject();
        metadata.set("title", gallery.getTitle());
        metadata.set("titleLock", true);
        if (gallery.getTags() != null && !gallery.getTags().isEmpty()) {
            metadata.set("tags", gallery.getTags());
            metadata.set("tagsLock", true);
        }
        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/books/" + bookId + "/metadata";
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(komgaUrl))
                    .header("X-API-Key", netConfig.getKomga().getApiKey())
                    .header("Content-Type", "application/json")
                    .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8))
                    .build();

            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int status = response.statusCode();

            // Komga PATCH 成功时通常返回 204 No Content。
            if (!(status == 204 || (status >= 200 && status < 300))) {
                log.error("❌ Komga 元数据更新失败, GID: {}, BookID: {}, HTTP: {}, body: {}",
                        gid, bookId, status, response.body());
                throw ApplicationFailure.newFailure(
                        "Komga metadata patch failed: HTTP " + status,
                        "KOMGA_METADATA_PATCH_FAILED"
                );
            }
        } catch (Exception e) {
            log.error("❌ 调用 Komga PATCH 接口异常, GID: {}, BookID: {}", gid, bookId, e);
            throw ApplicationFailure.newFailure(
                    "Komga metadata patch exception: " + e.getMessage(),
                    "KOMGA_METADATA_PATCH_EXCEPTION"
            );
        }

        // 记录入库成功 (此时存进库里的正好是准确的 BookId)
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setKomgaBookId(bookId);
        updateEntity.setDownloadStatus("已入库");
        galleriesMapper.updateById(updateEntity);

        log.info("🎉 GID: {} 已成功虚拟重命名并打上标签! BookID: {}", gid, bookId);
    }

    @Override
    public void triggerKomgaLibraryScan() {
        String libraryId = netConfig.getKomga().getLibraryId();
        if (StrUtil.isBlank(libraryId)) {
            log.warn("⚠️ 未配置 Komga Library ID，跳过主动扫描触发。");
            return;
        }

        String scanUrl = netConfig.getKomga().getUrl() + "/api/v1/libraries/" + libraryId + "/scan";

        HttpResponse response = HttpRequest.post(scanUrl)
                .header("X-API-Key", netConfig.getKomga().getApiKey())
                .execute();

        // API 规范中定义的成功响应是 202 Accepted
        if (response.isOk() || response.getStatus() == 202) {
            log.info("🚀 已成功向 Komga 发送主动扫描指令，Library ID: {}", libraryId);
        } else {
            log.error("❌ 触发 Komga 扫描失败, HTTP 状态码: {}", response.getStatus());
        }
    }

    @Override
    public String renameSynologyFile(Long gid, String oldFilename) {
        if (StrUtil.isBlank(oldFilename)) return null;

        // 🚀 核心防御：如果文件名已经包含了 [GID] 前缀，跳过重命名
        if (oldFilename.startsWith("[" + gid + "]")) {
            log.info("✅ 文件已包含 GID 前缀，跳过重命名: {}", oldFilename);
            return oldFilename;
        }

        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) return null;

        // 1. 提取后缀名 (用于新文件名)
        String ext = ".zip"; // 默认兜底后缀
        if (oldFilename.matches("(?i).*\\.(zip|cbz|rar)$")) {
            // 只有当源文件真的带常规压缩包后缀时，才提取它
            ext = oldFilename.substring(oldFilename.lastIndexOf('.'));
        }

        // 2. 构建安全的新文件名
        String safeTitle = gallery.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 80) {
            safeTitle = safeTitle.substring(0, 80);
        }

        // 无论原物理文件有没有后缀，新文件都会被强行赋予 .zip 或原有后缀！
        String newFilename = "[" + gid + "] " + safeTitle + ext;

        try {
            // 3. 获取群晖 SID
            String sid = getSynologySid();
            String synoUrl = netConfig.getSynology().getUrl() + "/webapi/entry.cgi";

            // 4. 组装物理绝对路径
            String dest = netConfig.getSynology().getDestination(); // e.g., n8n_bot/EHentai
            if (!dest.startsWith("/")) {
                dest = "/" + dest;
            }
            String oldFilePath = dest + "/" + oldFilename; // 使用纯正无污染的原始名字找文件

            // 5. 调用 File Station Rename API
            Map<String, Object> params = new HashMap<>();
            params.put("api", "SYNO.FileStation.Rename");
            params.put("version", "2");
            params.put("method", "rename");

            params.put("path", new cn.hutool.json.JSONArray().set(oldFilePath).toString());
            params.put("name", new cn.hutool.json.JSONArray().set(newFilename).toString());
            params.put("_sid", sid);

            HttpResponse response = HttpRequest.post(synoUrl).form(params).execute();
            if (response.isOk() && JSONUtil.parseObj(response.body()).getBool("success", false)) {
                log.info("✅ 群晖物理文件重命名成功: {} -> {}", oldFilename, newFilename);

                // 将新文件名更新到数据库
                EhGalleriesEntity updateEntity = new EhGalleriesEntity();
                updateEntity.setGid(gid);
                updateEntity.setFilename(newFilename);
                galleriesMapper.updateById(updateEntity);

                return newFilename;
            } else {
                log.error("❌ 群晖 API 重命名失败 (可能遇到 412 物理死结): {}", response.body());

                String safePrefix = oldFilename;
                if (safePrefix.length() > 60) {
                    safePrefix = safePrefix.substring(0, 60);
                }
                boolean sshSuccess = renameViaSSH(safePrefix, newFilename);
                if (sshSuccess) {
                    // SSH 改名成功，一样更新数据库并返回！
                    EhGalleriesEntity updateEntity = new EhGalleriesEntity();
                    updateEntity.setGid(gid);
                    updateEntity.setFilename(newFilename);
                    galleriesMapper.updateById(updateEntity);
                    return newFilename;
                }
            }
        } catch (Exception e) {
            log.error("调用群晖重命名接口发生异常", e);
        }
        return oldFilename; // 如果失败，退化为旧文件名
    }

    /**
     * 通过 SSH 直接调用 Linux 底层 mv + rm 强制改名并清理残留
     */
    private boolean renameViaSSH(String safePrefix, String newFilename) {
        // 🚀 核心修复：使用原生 URL 类安全提取 Host，彻底抛弃脆弱的正则
        String host = "10.10.10.40"; // 默认兜底 IP
        try {
            host = new URL(netConfig.getSynology().getUrl()).getHost();
        } catch (Exception e) {
            log.warn("⚠️ 解析群晖 URL 获取 Host 失败，将使用默认 IP 进行 SSH 连接", e);
        }

        int port = 22;
        String user = netConfig.getSynology().getUsername();
        String pass = netConfig.getSynology().getPassword();

        Session session = null;
        try {
            log.warn("⚠️ 触发终极兜底引擎：尝试通过 SSH 连接群晖底层执行通配符改名... 目标IP: {}", host);
            session = JschUtil.getSession(host, port, user, pass);

            String dest = netConfig.getSynology().getDestination();
            if (!dest.startsWith("/")) dest = "/" + dest;

            String volume = "/volume1";
            String physicalPath = volume + dest;

            // 组装命令，安全包裹双引号
            String command = String.format("mv \"%s/%s\"* \"%s/%s\" && rm -rf \"%s/%s\"*",
                    physicalPath, safePrefix,
                    physicalPath, newFilename,
                    physicalPath, safePrefix);

            log.info("💻 执行 SSH 强制指令: {}", command);

            String result = JschUtil.exec(session, command, StandardCharsets.UTF_8);

            if (StrUtil.isBlank(result) || !result.toLowerCase().contains("cannot stat")) {
                log.info("✅ SSH 底层重命名与清理执行成功！");
                return true;
            } else {
                log.error("❌ SSH 底层重命名失败，系统返回: {}", result);
                return false;
            }
        } catch (Exception e) {
            log.error("SSH 兜底改名发生异常", e);
            return false;
        } finally {
            if (session != null) {
                JschUtil.close(session);
            }
        }
    }
}
