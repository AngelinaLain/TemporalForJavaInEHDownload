package com.checker.service.mail;

import com.checker.common.DownloadStatus;
import com.checker.config.EhNetworkConfig;
import com.checker.dto.BatchEmailPayload;
import com.checker.mapper.NotificationGalleryMapper;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把新旧通知整理成统一邮件数据，补充真实任务概况并过滤不适合外发的错误细节。 */
@Component
public class NotificationEmailComposer {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailComposer.class);
    private static final Pattern GID = Pattern.compile("(?im)^\\s*(?:[-•]\\s*)?GID\\s*[:：=]\\s*([1-9][0-9]{0,18})\\b");
    private static final Pattern WORKFLOW_GID = Pattern.compile(
            "^(?:single-gallery|retry-gallery|komga-import|dedupe-review|komga-review|archive-sync-redownload)-([1-9][0-9]{0,18})(?:-[0-9a-fA-F-]{36})?$");
    private static final Pattern LEGACY_METRIC = Pattern.compile("(?m)^(本次发现|计划子流程|实际启动|未启动|最终状态记录)[:：]\\s*([0-9]+)");
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)https?://[^\\s<>\\\"']+");
    private static final Pattern SECRET_HEADER = Pattern.compile("(?im)(^\\s*(?:authorization|cookie|set-cookie)\\s*[:=：]\\s*)[^\\r\\n]*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)([\\\"']?(?:authorization|cookie|set-cookie|client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|token|密码|密钥)[\\\"']?\\s*[:=：]\\s*)"
                    + "(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\r\\n,}\\]]+)" );
    private static final Pattern LOCAL_PATH = Pattern.compile("(?i)(?:[a-z]:[\\\\/]|\\\\\\\\)[^\\s<>]+|/(?:data|mnt|volume[0-9]*|tmp|opt|home|srv|var|app)/[^\\s<>]+");
    private final EhNetworkConfig config;
    private final NotificationGalleryMapper galleries;

    public NotificationEmailComposer(EhNetworkConfig config, NotificationGalleryMapper galleries) {
        this.config = config;
        this.galleries = galleries;
    }

    /** 新汇总只使用本批快照；旧文本只有明确 GID 或已知单本工作流身份时才查画廊。 */
    public NotificationEmail compose(String subject, String content, NotificationContext context) {
        NotificationContext actual = context == null ? NotificationContext.now() : context;
        String safeSubject = text(subject == null || subject.isBlank() ? "GalleryImport 系统通知" : subject, 200).replaceAll("[\\r\\n]+", " ");
        if (content != null && content.startsWith(BatchEmailPayload.PREFIX)) {
            try {
                return batch(safeSubject, BatchEmailPayload.decode(content), actual);
            } catch (IllegalArgumentException invalid) {
                // 编码不完整时仍发出可读通知，不把原始结构化数据或解析异常堆栈外发。
                return simple(safeSubject, "批次汇总内容暂时无法解析，请到系统查看任务结果。", actual,
                        List.of("汇总数据格式不完整，本邮件不推测任何画廊的结果。"));
            }
        }
        return simple(safeSubject, content, actual, List.of());
    }

    /** 统计使用完整批次数据，明细最多三十条；两者分别展示，避免截断明细导致数量失真。 */
    private NotificationEmail batch(String subject, BatchEmailPayload batch, NotificationContext context) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        batch.statusCounts().forEach((status, count) -> {
            if (count != null && count > 0) counts.merge(statusLabel(status), count, Integer::sum);
        });
        int imported = count(counts, DownloadStatus.IMPORTED);
        int failed = count(counts, DownloadStatus.DOWNLOAD_FAILED) + count(counts, DownloadStatus.KOMGA_IMPORT_FAILED)
                + count(counts, DownloadStatus.PARTIAL) + count(counts, DownloadStatus.BLOCKED);
        int attention = counts.entrySet().stream().filter(entry -> !"success".equals(statusLevel(entry.getKey())))
                .mapToInt(Map.Entry::getValue).sum();
        int notStarted = Math.max(0, batch.planned() - batch.started());
        boolean incomplete = batch.totalGalleries() < batch.planned() || notStarted > 0;
        String level = batch.stoppedByFatalError() || failed > 0 && imported == 0 ? "danger"
                : attention > 0 || incomplete ? "warning" : "success";
        List<NotificationEmail.Metric> metrics = List.of(metric("发现画廊", batch.discovered()), metric("计划处理", batch.planned()),
                metric("实际启动", batch.started()), metric("已入库", imported), metric("需关注", attention), metric("未启动", notStarted));
        List<NotificationEmail.StatusCount> statuses = counts.entrySet().stream()
                .map(entry -> new NotificationEmail.StatusCount(entry.getKey(), entry.getValue(), statusLevel(entry.getKey()))).toList();
        List<String> notices = new ArrayList<>();
        if (batch.stoppedByFatalError()) notices.add("任务遇到阻断错误，后续尚未启动的子流程已停止派发。请先处理源站登录、配额或连接问题。" );
        if (notStarted > 0) notices.add("有 " + notStarted + " 个计划子流程未启动；请结合去重审核和流程监控核对，不直接计作下载失败。" );
        if (batch.totalGalleries() < batch.planned()) notices.add("有 " + (batch.planned() - batch.totalGalleries()) + " 条计划记录未出现在最终状态快照中，请在系统核对。" );
        if (failed > 0) notices.add("失败、不完整或阻断的记录已优先展示。没有保存具体原因的记录会标明缺失，请到流程监控查看活动日志。" );
        if (count(counts, DownloadStatus.KOMGA_IMPORT_FAILED) + count(counts, DownloadStatus.WAITING_KOMGA) > 0) {
            notices.add("已上传但未确认入库的画廊，可在“Komga 入库复核”检查关联并重试，无需立即重新下载。" );
        }
        if (count(counts, DownloadStatus.REVIEW_REQUIRED) > 0) notices.add("待去重审核的候选需要人工确认；邮件中的推荐信息不会自动提交审核结论。" );
        List<NotificationEmail.Gallery> details = batch.galleries().stream().limit(30).map(this::gallery).toList();
        int omitted = Math.max(Math.max(0, batch.omittedGalleries()), Math.max(0, batch.totalGalleries() - details.size()));
        String summary = text(batch.flowName(), 80) + "已结束：状态快照共 " + Math.max(0, batch.totalGalleries()) + " 条，已入库 "
                + imported + " 条，需关注 " + attention + " 条。";
        String timing = "";
        if (batch.startedAt() > 0) timing += "开始时间：" + time(batch.startedAt()) + "\n";
        if (batch.finishedAt() > 0) timing += "结束时间：" + time(batch.finishedAt()) + "\n";
        timing += "画廊状态来自本批结束时快照；明细优先展示异常和待处理记录，不代表全库状态。";
        return new NotificationEmail(subject, "批次报告", level, summary,
                time(batch.finishedAt() > 0 ? batch.finishedAt() : context.triggeredAt()),
                duration(batch.startedAt(), batch.finishedAt()), text(batch.workflowId(), 240), text(batch.runId(), 128),
                metrics, statuses, details, omitted, notices, links(counts), timing);
    }

    /** 兼容普通告警与旧汇总，原文保留为详情，不把当前全库数据冒充本次处理结果。 */
    private NotificationEmail simple(String subject, String content, NotificationContext context, List<String> extraNotices) {
        String detail = text(content == null || content.isBlank() ? "此通知未提供附加正文。" : content, 12000);
        boolean test = subject.contains("测试");
        String level = test ? "info" : subject.matches(".*(阻断|致命|❌).*" ) ? "danger"
                : subject.matches(".*(失败|异常|超时|不完整|⚠).*" ) ? "warning" : "info";
        List<String> notices = new ArrayList<>(extraNotices);
        List<NotificationEmail.Gallery> details = new ArrayList<>();
        Set<Long> ids = new LinkedHashSet<>();
        Matcher workflow = WORKFLOW_GID.matcher(context.workflowId() == null ? "" : context.workflowId());
        if (workflow.matches()) {
            // 已知的单本任务身份优先，不能把异常 URL 或正文顺带提到的其他 GID 混成本次画廊。
            addGid(ids, workflow.group(1));
        } else {
            Matcher matcher = GID.matcher(content == null ? "" : content);
            while (matcher.find() && ids.size() < 10) addGid(ids, matcher.group(1));
        }
        if (!ids.isEmpty()) {
            try {
                List<BatchEmailPayload.Gallery> snapshots = galleries.findForNotification(List.copyOf(ids));
                snapshots.stream().limit(10).map(this::gallery).forEach(details::add);
                notices.add("画廊概况为发信时查询的当前记录；通知正文保留触发时的描述。" );
                if (snapshots.size() < ids.size()) notices.add("部分指定 GID 当前没有数据库记录，请在系统核对。" );
            } catch (RuntimeException failure) {
                log.warn("邮件画廊概况查询失败，仅发送原通知，错误类型: {}", failure.getClass().getSimpleName());
                notices.add("暂时无法读取画廊概况，原通知仍保留；请到系统核对详情。" );
            }
        }
        List<NotificationEmail.Metric> metrics = new ArrayList<>();
        Matcher oldMetrics = LEGACY_METRIC.matcher(detail);
        while (oldMetrics.find() && metrics.size() < 6) metrics.add(new NotificationEmail.Metric(oldMetrics.group(1), oldMetrics.group(2)));
        if (test) notices.add("这是一封通知样式测试。成功收到仅表示本次发信链路可用，实际任务结果请查看正式汇总。" );
        if (!test && !"info".equals(level)) notices.add("请结合流程监控核对错误；入库问题可先在 Komga 入库复核处理，避免重复下载。" );
        String summary = test ? "邮件通知链路测试，以下展示本次提交的测试内容。"
                : "批次或单条任务已产生通知，请查看下方详情与可用的处理入口。";
        return new NotificationEmail(subject, test ? "通知测试" : "任务通知", level, summary, time(context.triggeredAt()), "",
                text(context.workflowId(), 240), text(context.runId(), 128), metrics, List.of(), details, 0,
                notices, links(Map.of()), detail);
    }

    /** 只展示有数据的尺寸、评分和版本关系；缺失原因不替换成推测性的错误描述。 */
    private NotificationEmail.Gallery gallery(BatchEmailPayload.Gallery item) {
        List<String> facts = new ArrayList<>();
        if (item.pageCount() != null && item.pageCount() > 0) facts.add(item.pageCount() + " 页");
        if (item.rating() != null && Double.isFinite(item.rating())) facts.add("评分 " + String.format(Locale.ROOT, "%.1f / 5", item.rating()));
        if (item.fileSizeMb() != null && Double.isFinite(item.fileSizeMb()) && item.fileSizeMb() >= 0) facts.add(String.format(Locale.ROOT, "%.1f MB", item.fileSizeMb()));
        if (item.duplicateOfGid() != null) facts.add("重复版本 → GID " + item.duplicateOfGid());
        if (item.confirmationAttempts() != null && item.confirmationAttempts() > 0) facts.add("入库确认 " + item.confirmationAttempts() + " 次");
        String status = statusLabel(item.status());
        String reason = text(item.reason(), 500);
        if (reason.isBlank() && "danger".equals(statusLevel(status))) reason = "未保存具体失败原因，请到流程监控查看对应活动日志。";
        String title = text(item.title(), 240);
        if (title.isBlank()) title = "未提供标题";
        String rowUrl = appUrl("galleries", item.gid());
        if (rowUrl.isBlank()) rowUrl = komgaUrl(item.komgaBookId());
        return new NotificationEmail.Gallery(item.gid(), title, text(item.originalTitle(), 240), status, statusLevel(status),
                String.join(" · ", facts), reason, rowUrl);
    }

    private List<NotificationEmail.Link> links(Map<String, Integer> counts) {
        List<NotificationEmail.Link> links = new ArrayList<>();
        addLink(links, "打开系统", appUrl("dashboard", null));
        addLink(links, "查看流程监控", appUrl("workflows", null));
        if (count(counts, DownloadStatus.KOMGA_IMPORT_FAILED) + count(counts, DownloadStatus.WAITING_KOMGA) > 0) addLink(links, "Komga 入库复核", appUrl("komga-import-reviews", null));
        if (count(counts, DownloadStatus.REVIEW_REQUIRED) > 0) addLink(links, "处理去重审核", appUrl("dedupe-reviews", null));
        return links;
    }

    private void addLink(List<NotificationEmail.Link> links, String label, String url) {
        if (!url.isBlank()) links.add(new NotificationEmail.Link(label, url));
    }

    /** 公共入口仅允许 HTTP/HTTPS，清除查询和片段，拒绝地址中夹带的登录凭据。 */
    private HttpUrl base(String url) {
        HttpUrl parsed = HttpUrl.parse(url == null ? "" : url);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) return null;
        return parsed.newBuilder().query(null).fragment(null).build();
    }

    private String appUrl(String route, Long gid) {
        HttpUrl base = base(config.getNotification().getAppPublicUrl());
        if (base == null) return "";
        HttpUrl.Builder url = base.newBuilder();
        if (base.encodedPath().endsWith("/")) url.removePathSegment(base.pathSize() - 1);
        url.addPathSegment(route);
        if (gid != null && gid > 0) url.addQueryParameter("gid", gid.toString()).addQueryParameter("dedupe", "all");
        return url.build().toString();
    }

    private String komgaUrl(String bookId) {
        if (bookId == null || bookId.isBlank()) return "";
        String publicUrl = config.getKomga().getPublicUrl();
        HttpUrl base = base(publicUrl == null || publicUrl.isBlank() ? config.getKomga().getUrl() : publicUrl);
        if (base == null) return "";
        HttpUrl.Builder url = base.newBuilder();
        if (base.encodedPath().endsWith("/")) url.removePathSegment(base.pathSize() - 1);
        return url.addPathSegment("book").addPathSegment(bookId).build().toString();
    }

    private int count(Map<String, Integer> counts, DownloadStatus status) {
        return counts.getOrDefault(status.getValue(), 0);
    }

    private NotificationEmail.Metric metric(String label, int value) {
        return new NotificationEmail.Metric(label, Integer.toString(Math.max(0, value)));
    }

    private String statusLabel(String value) {
        for (DownloadStatus status : DownloadStatus.values()) {
            if (status.name().equals(value) || status.getValue().equals(value)) return status.getValue();
        }
        return "未知状态";
    }

    private String statusLevel(String value) {
        String label = statusLabel(value);
        if (DownloadStatus.IMPORTED.getValue().equals(label) || DownloadStatus.IGNORED.getValue().equals(label)) return "success";
        if (DownloadStatus.BLOCKED.getValue().equals(label) || DownloadStatus.DOWNLOAD_FAILED.getValue().equals(label)
                || DownloadStatus.KOMGA_IMPORT_FAILED.getValue().equals(label) || DownloadStatus.PARTIAL.getValue().equals(label)) return "danger";
        return "warning";
    }

    private void addGid(Set<Long> ids, String value) {
        try { long gid = Long.parseLong(value); if (gid > 0) ids.add(gid); }
        catch (NumberFormatException ignored) { /* 超过数据库编号范围的文本不参与查询。 */ }
    }

    /** 只对展示文本脱敏，所有 HTML 转义仍由渲染器统一完成，避免重复转义。 */
    private String text(String raw, int maximum) {
        if (raw == null) return "";
        String safe = URL_IN_TEXT.matcher(raw).replaceAll("[链接已省略]");
        safe = SECRET_HEADER.matcher(safe).replaceAll("$1[已隐藏]");
        safe = SECRET.matcher(safe).replaceAll("$1[已隐藏]");
        safe = LOCAL_PATH.matcher(safe).replaceAll("[内部路径已省略]").replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");
        int size = safe.codePointCount(0, safe.length());
        return size <= maximum ? safe : safe.substring(0, safe.offsetByCodePoints(0, maximum)) + "…（已截断，请在系统查看完整信息）";
    }

    private String time(long millis) {
        ZoneId zone;
        try { zone = ZoneId.of(config.getNotification().getTimeZone()); }
        catch (DateTimeException | NullPointerException invalid) { zone = ZoneId.of("Asia/Shanghai"); }
        var time = Instant.ofEpochMilli(millis).atZone(zone);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(time)
                + "（" + zone.getId() + " · UTC" + time.getOffset().getId().replace("Z", "+00:00") + "）";
    }

    private String duration(long start, long finish) {
        if (start <= 0 || finish < start) return "";
        long seconds = (finish - start) / 1000;
        if (seconds < 60) return seconds + " 秒";
        if (seconds < 3600) return seconds / 60 + " 分 " + seconds % 60 + " 秒";
        return seconds / 3600 + " 小时 " + seconds % 3600 / 60 + " 分";
    }
}
