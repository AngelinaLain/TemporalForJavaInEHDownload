package com.checker.service.mail;

import com.checker.config.EhNetworkConfig;
import com.checker.dto.BatchEmailPayload;
import com.checker.mapper.NotificationGalleryMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationEmailComposerTest {
    private static final long START = Instant.parse("2026-10-29T06:00:00Z").toEpochMilli();
    private final EhNetworkConfig config = new EhNetworkConfig();
    private final NotificationGalleryMapper galleries = mock(NotificationGalleryMapper.class);
    private final NotificationEmailComposer composer = new NotificationEmailComposer(config, galleries);
    private final NotificationContext context = new NotificationContext("eh-auto-fixture", "run-fixture", START + 75_000);

    @Test void newBatchUsesItsOwnCompleteCountsAndNeverQueriesCurrentDatabaseState() {
        config.getNotification().setAppPublicUrl("https://gallery.example/library/?old=private#fragment");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("IMPORTED", 20); counts.put("已入库", 5); counts.put("KOMGA_IMPORT_FAILED", 2);
        counts.put("REVIEW_REQUIRED", 1); counts.put("已忽略", 7);
        List<BatchEmailPayload.Gallery> details = IntStream.rangeClosed(1, 35)
                .mapToObj(index -> gallery(index, "IMPORTED", "已确认")).toList();
        NotificationEmail email = composer.compose("抓取流程汇总", batch(counts, 35, 0, details).encode(), context);

        assertEquals("warning", email.level());
        assertEquals("25", metric(email, "已入库"));
        assertEquals("3", metric(email, "需关注"));
        assertEquals(30, email.galleries().size());
        assertEquals(5, email.omittedGalleries());
        assertEquals(35, email.statuses().stream().mapToInt(NotificationEmail.StatusCount::count).sum());
        assertTrue(email.summary().contains("状态快照共 35 条"));
        assertEquals("1 分 15 秒", email.duration());
        assertTrue(email.generatedAt().contains("2026-10-29 14:01:15"));
        assertTrue(email.generatedAt().contains("Asia/Shanghai · UTC+08:00"));
        assertEquals("https://gallery.example/library/galleries?gid=1&dedupe=all", email.galleries().get(0).url());
        assertTrue(email.links().stream().anyMatch(link -> link.label().equals("Komga 入库复核")));
        assertTrue(email.links().stream().anyMatch(link -> link.label().equals("处理去重审核")));
        assertTrue(email.links().stream().noneMatch(link -> link.url().contains("private") || link.url().contains("fragment")));
        verifyNoInteractions(galleries);
    }

    @Test void emptyBatchIsValidButMissingFinalRecordsAreNeverReportedAsAnUnqualifiedSuccess() {
        NotificationEmail empty = composer.compose("抓取流程汇总", batch(Map.of(), 0, 0, List.of()).encode(), context);
        assertEquals("success", empty.level());
        assertEquals("0", metric(empty, "已入库"));
        assertTrue(empty.galleries().isEmpty());
        BatchEmailPayload missing = new BatchEmailPayload("抓取流程", 4, 4, 4, false, START, START + 1000,
                "workflow", "run", Map.of(), 0, 0, List.of());
        NotificationEmail email = composer.compose("抓取流程汇总", missing.encode(), context);
        assertEquals("warning", email.level());
        assertTrue(email.notices().stream().anyMatch(note -> note.contains("4 条计划记录")));
        verifyNoInteractions(galleries);
    }

    @Test void blockingAndUnknownStatesHaveExplicitAttentionAndMissingReasonsRemainHonest() {
        BatchEmailPayload blocked = new BatchEmailPayload("重试流程", 2, 2, 1, true, START, START + 1000,
                "workflow", "run", Map.of("BLOCKED", 1, "unrecognized", 1), 2, 0,
                List.of(gallery(1, "BLOCKED", null), gallery(2, "unrecognized", null)));
        NotificationEmail email = composer.compose("重试流程汇总", blocked.encode(), context);
        assertEquals("danger", email.level());
        assertEquals("2", metric(email, "需关注"));
        assertEquals("1", metric(email, "未启动"));
        assertTrue(email.galleries().get(0).reason().contains("未保存具体失败原因"));
        assertEquals("未知状态", email.galleries().get(1).status());
        assertTrue(email.notices().stream().anyMatch(note -> note.contains("停止派发")));
    }

    @Test void oldSummaryKeepsTheRealTextAndDoesNotGuessGalleryIdentityFromATitleOrRecentRows() {
        String body = "抓取流程已结束\n本次发现: 10 个画廊\n计划子流程: 5 个\n状态汇总:\n- 已入库: 4\n画廊: 同名作品";
        NotificationEmail email = composer.compose("抓取流程汇总", body, context);
        assertEquals(body, email.detail());
        assertTrue(email.galleries().isEmpty());
        assertEquals("10", metric(email, "本次发现"));
        assertEquals("5", metric(email, "计划子流程"));
        verifyNoInteractions(galleries);
    }

    @Test void knownSingleGalleryWorkflowTakesPriorityOverOtherIdsMentionedInItsAlert() {
        NotificationContext one = new NotificationContext("archive-sync-redownload-43-12345678-1234-1234-1234-123456789abc", "run", START);
        when(galleries.findForNotification(List.of(43L))).thenReturn(List.of(gallery(43, "WAITING_KOMGA", "仍在扫描")));
        NotificationEmail email = composer.compose("下载异常", "GID: 42\nGID：42\nGID: 9223372036854775808", one);
        verify(galleries).findForNotification(List.of(43L));
        assertEquals(1, email.galleries().size());
        assertEquals(43L, email.galleries().get(0).gid());
        assertTrue(email.notices().stream().anyMatch(note -> note.contains("发信时查询的当前记录")));
        assertTrue(email.galleries().get(0).facts().contains("评分 4.5 / 5"));
        assertTrue(email.galleries().get(0).facts().contains("28 页"));
        assertTrue(email.galleries().get(0).facts().contains("12.0 MB"));
        verifyNoMoreInteractions(galleries);
    }

    @Test void standaloneIdLinesAreDeduplicatedWhileOverflowAndEmbeddedUrlIdsAreIgnored() {
        when(galleries.findForNotification(List.of(42L, 43L))).thenReturn(List.of(gallery(42, "IMPORTED", null), gallery(43, "IMPORTED", null)));
        NotificationEmail email = composer.compose("下载异常", "GID: 42\nGID：42\n- GID=43\nGID: 9223372036854775808\n"
                + "请求 https://example.test/api?gid=99\n画廊标题 GID: 100", context);
        assertEquals(2, email.galleries().size());
        verify(galleries).findForNotification(List.of(42L, 43L));
        verifyNoMoreInteractions(galleries);
    }

    @Test void urlParametersAndGidMentionedInsideATitleCannotIdentifyANotificationGallery() {
        composer.compose("下载异常", "画廊: 原始标题 GID: 42\nhttps://example.test/preview?gid=43", context);
        verifyNoInteractions(galleries);
    }

    @Test void unknownWorkflowPrefixDoesNotCauseLookupAndDatabaseFailureKeepsOriginalAlert() {
        NotificationContext unknown = new NotificationContext("user-job-42", "run", START);
        composer.compose("下载异常", "画廊: 同名作品", unknown);
        verifyNoInteractions(galleries);
        when(galleries.findForNotification(List.of(42L))).thenThrow(new IllegalStateException("fixture database unavailable"));
        NotificationEmail email = composer.compose("下载异常", "GID: 42\n下载连续失败", unknown);
        assertTrue(email.detail().contains("下载连续失败"));
        assertTrue(email.galleries().isEmpty());
        assertTrue(email.notices().stream().anyMatch(note -> note.contains("暂时无法读取画廊概况")));
    }

    @Test void legacyLookupNeverExceedsTenDistinctIds() {
        String body = IntStream.rangeClosed(1, 20).mapToObj(index -> "GID: " + index).collect(java.util.stream.Collectors.joining("\n"));
        List<Long> ids = IntStream.rangeClosed(1, 10).mapToObj(index -> (long) index).toList();
        when(galleries.findForNotification(ids)).thenReturn(List.of());
        composer.compose("通知", body, context);
        verify(galleries).findForNotification(ids);
        verifyNoMoreInteractions(galleries);
    }

    @Test void publicUrlsKeepDeploymentPrefixesAndRejectCredentialsOrScriptSchemes() {
        for (String invalid : List.of("https://user:password@app.example/", "javascript:alert(1)", "file:///tmp/example")) {
            config.getNotification().setAppPublicUrl(invalid);
            config.getKomga().setPublicUrl(invalid);
            NotificationEmail email = composer.compose("汇总", batch(Map.of("IMPORTED", 1), 1, 0, List.of(gallery(1, "IMPORTED", null))).encode(), context);
            assertTrue(email.links().isEmpty(), invalid);
            assertTrue(email.galleries().get(0).url().isEmpty(), invalid);
        }
        config.getNotification().setAppPublicUrl("");
        config.getKomga().setPublicUrl("https://komga.example/reader/?private=secret#fragment");
        NotificationEmail email = composer.compose("汇总", batch(Map.of("IMPORTED", 1), 1, 0, List.of(gallery(1, "IMPORTED", null))).encode(), context);
        assertEquals("https://komga.example/reader/book/book-1", email.galleries().get(0).url());
    }

    @Test void notificationTextRedactsCredentialsInPlainAndJsonErrorsAndInternalPaths() {
        String body = "Authorization: Bearer plain-private\nCookie: \"first=cookie-first\"; second=cookie-second\n"
                + "{\"access_token\":\"json-private\"}\n{\"Authorization\":\"Bearer json-bearer\"}\n"
                + "https://user:pass@nas.example/file?token=url-private\n"
                + "C:\\private\\library\\book.cbz\n/volume1/library/book.cbz\n普通失败原因";
        NotificationEmail email = composer.compose("下载异常\r\n非法换行", body, context);
        for (String secret : List.of("plain-private", "cookie-first", "cookie-second", "json-private", "json-bearer", "url-private", "nas.example", "C:\\private", "/volume1/library")) {
            assertFalse(email.detail().contains(secret), secret);
        }
        assertTrue(email.detail().contains("普通失败原因"));
        assertFalse(email.subject().contains("\n"));
        assertTrue(email.detail().contains("已隐藏"));
        verifyNoInteractions(galleries);
    }

    @Test void malformedStructuredContentDoesNotLeakItsRawPayloadAndInvalidTimezoneHasAnExplicitFallback() {
        config.getNotification().setTimeZone("invalid-fixture-zone");
        NotificationEmail email = composer.compose("汇总", BatchEmailPayload.PREFIX + "{broken-private-payload}", context);
        assertTrue(email.detail().contains("暂时无法解析"));
        assertFalse(email.detail().contains("broken-private-payload"));
        assertTrue(email.generatedAt().contains("Asia/Shanghai · UTC+08:00"));
        config.getNotification().setTimeZone("UTC");
        NotificationEmail utc = composer.compose("邮件测试", "测试正文", context);
        assertTrue(utc.generatedAt().contains("2026-10-29 06:01:15"));
        assertTrue(utc.generatedAt().contains("UTC+00:00"));
        assertEquals("通知测试", utc.category());
        assertTrue(utc.notices().stream().anyMatch(note -> note.contains("仅表示本次发信链路可用")));
    }

    private BatchEmailPayload batch(Map<String, Integer> counts, int total, int omitted, List<BatchEmailPayload.Gallery> details) {
        return new BatchEmailPayload("抓取流程", total, total, total, false, START, START + 75_000,
                "eh-auto-fixture", "run-fixture", counts, total, omitted, details);
    }

    private BatchEmailPayload.Gallery gallery(long gid, String status, String reason) {
        return new BatchEmailPayload.Gallery(gid, "测试画廊 " + gid, "原始标题 " + gid, status,
                28, 4.5, 12.0, null, 3, reason, "book-" + gid);
    }

    private String metric(NotificationEmail email, String name) {
        return email.metrics().stream().filter(metric -> metric.label().equals(name)).findFirst().orElseThrow().value();
    }
}
