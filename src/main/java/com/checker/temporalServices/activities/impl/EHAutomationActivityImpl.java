package com.checker.temporalServices.activities.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
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
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
                    // 🚀 核心升级 1：提取群晖真实的下载文件名，并保存到数据库供 Komga 匹配
                    String taskTitle = task.getStr("title", ""); // 例如：_Yaseuma_Loru__..._.zip
                    if (StrUtil.isNotBlank(taskTitle)) {
                        // 剥离后缀名 .zip / .cbz / .rar
                        String pureName = taskTitle.replaceAll("(?i)\\.(zip|cbz|rar)$", "");
                        EhGalleriesEntity updateFile = new EhGalleriesEntity();
                        updateFile.setGid(gid);
                        updateFile.setFilename(pureName); // 覆盖掉之前程序估算的文件名
                        galleriesMapper.updateById(updateFile);
                        log.info("💾 已记录群晖真实文件名: {}", pureName);
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

    // 2. 轮询查找 Komga 中的 Series ID
    @Override
    public String findBookInKomga(String realFilename) {
        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/series/list";

        // 🚀 核心升级 2：使用 Komga 的 Condition 进行 100% 精确匹配
        cn.hutool.json.JSONObject searchBody = new cn.hutool.json.JSONObject();
        cn.hutool.json.JSONObject condition = new cn.hutool.json.JSONObject();
        cn.hutool.json.JSONObject titleCond = new cn.hutool.json.JSONObject();

        titleCond.set("operator", "Is"); // 绝对相等
        titleCond.set("value", realFilename); // 使用刚从群晖拿到的真实物理文件名
        condition.set("title", titleCond);
        searchBody.set("condition", condition);

        HttpResponse response = HttpRequest.post(komgaUrl)
                .basicAuth(netConfig.getKomga().getUsername(), netConfig.getKomga().getPassword())
                .header("Content-Type", "application/json")
                .body(searchBody.toString())
                .execute();

        if (response.isOk()) {
            cn.hutool.json.JSONObject resObj = cn.hutool.json.JSONUtil.parseObj(response.body());
            cn.hutool.json.JSONArray content = resObj.getJSONArray("content");
            if (content != null && !content.isEmpty()) {
                // 找到了！直接返回
                return content.getJSONObject(0).getStr("id");
            }
        }
        return null;
    }

    // 3. 将元数据和完美标题推送给 Komga
    @Override
    public void pushMetadataToKomga(String seriesId, Long gid) {
        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);

        cn.hutool.json.JSONObject metadata = new cn.hutool.json.JSONObject();

        // 🌟 核心魔法 3：在这里进行“虚拟重命名”！
        // 用数据库里原始的、带方括号的精美标题，覆盖 Komga 界面上丑陋的下划线文件名
        metadata.set("title", gallery.getTitle());
        metadata.set("titleLock", true);
        metadata.set("titleSort", gallery.getTitle());
        metadata.set("titleSortLock", true);

        // 正常推送标签
        if (gallery.getTags() != null && !gallery.getTags().isEmpty()) {
            metadata.set("tags", gallery.getTags());
            metadata.set("tagsLock", true);
        }

        String komgaUrl = netConfig.getKomga().getUrl() + "/api/v1/series/" + seriesId + "/metadata";
        HttpRequest.patch(komgaUrl)
                .basicAuth(netConfig.getKomga().getUsername(), netConfig.getKomga().getPassword())
                .header("Content-Type", "application/json")
                .body(metadata.toString())
                .execute();

        // 记录入库成功
        EhGalleriesEntity updateEntity = new EhGalleriesEntity();
        updateEntity.setGid(gid);
        updateEntity.setKomgaBookId(seriesId);
        updateEntity.setDownloadStatus("已入库");
        galleriesMapper.updateById(updateEntity);
        log.info("🎉 GID: {} 已成功虚拟重命名并打上标签! SeriesID: {}", gid, seriesId);
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
                .basicAuth(netConfig.getKomga().getUsername(), netConfig.getKomga().getPassword())
                .execute();

        // API 规范中定义的成功响应是 202 Accepted
        if (response.isOk() || response.getStatus() == 202) {
            log.info("🚀 已成功向 Komga 发送主动扫描指令，Library ID: {}", libraryId);
        } else {
            log.error("❌ 触发 Komga 扫描失败, HTTP 状态码: {}", response.getStatus());
        }
    }
}
