package com.checker.temporalServices.workflows.impl;

import com.checker.dto.BatchEmailPayload;
import com.checker.entity.EhGalleriesEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStepsEmailTest {
    @Test
    void richSummaryUsesExactBatchCountsAndOnlyThirtyPrioritizedDetails() {
        List<EhGalleriesEntity> rows = new ArrayList<>();
        for (long gid = 40; gid > 0; gid--) rows.add(gallery(gid, "IMPORTED"));
        rows.add(gallery(44L, "WAITING_KOMGA"));
        rows.add(gallery(43L, "待去重审核"));
        rows.add(gallery(42L, "不完整"));
        rows.add(gallery(41L, "DOWNLOAD_FAILED"));
        String content = rich(rows);
        BatchEmailPayload report = BatchEmailPayload.decode(content);

        assertEquals(44, report.totalGalleries());
        assertEquals(14, report.omittedGalleries());
        assertEquals(30, report.galleries().size());
        assertEquals(40, report.statusCounts().get("已入库"));
        assertEquals(1, report.statusCounts().get("下载失败"));
        assertEquals(1, report.statusCounts().get("不完整"));
        assertEquals(1, report.statusCounts().get("待去重审核"));
        assertEquals(List.of(41L, 42L, 43L, 44L, 1L, 2L),
                report.galleries().stream().limit(6).map(BatchEmailPayload.Gallery::gid).toList());
        assertEquals(content, rich(rows));
        assertEquals(60, report.discovered());
        assertEquals(50, report.planned());
        assertEquals(47, report.started());
        assertTrue(report.stoppedByFatalError());
        assertEquals(1_000L, report.startedAt());
        assertEquals(11_000L, report.finishedAt());
        assertEquals("workflow-safe", report.workflowId());
    }

    @Test
    void preservesOldPlainTextSummaryForExistingHistories() {
        String content = WorkflowSteps.buildBatchNotificationContent("重试流程", 4, 3, 2,
                true, List.of(gallery(1L, "IMPORTED"), gallery(2L, "KOMGA_IMPORT_FAILED")));
        assertFalse(content.startsWith(BatchEmailPayload.PREFIX));
        assertTrue(content.contains("本次发现: 4 个画廊"));
        assertTrue(content.contains("未启动: 1 个"));
        assertTrue(content.contains("- 已入库: 1"));
        assertTrue(content.contains("- Komga 入库失败: 1"));
        assertTrue(content.contains("- 未查询到状态记录: 1"));
        assertTrue(content.contains("后续尚未启动的子流程已停止派发"));
    }

    @Test
    void handlesEmptyMissingAndUnknownStateRecordsWithoutInventingResults() {
        BatchEmailPayload empty = BatchEmailPayload.decode(rich(null));
        assertEquals(0, empty.totalGalleries());
        assertEquals(0, empty.omittedGalleries());
        assertTrue(empty.galleries().isEmpty());
        assertEquals(0, empty.statusCounts().values().stream().mapToInt(Integer::intValue).sum());

        BatchEmailPayload unknown = BatchEmailPayload.decode(rich(Arrays.asList(null, gallery(2L, "NOT_A_STATE"))));
        assertEquals(1, unknown.totalGalleries());
        assertEquals(1, unknown.statusCounts().get("未知状态"));
        assertEquals("未知状态", unknown.galleries().get(0).status());
        assertEquals(0, unknown.statusCounts().get("已入库"));
    }

    @Test
    void limitsUnicodeFieldsAndDoesNotSerializeCredentialsPathsOrTracePayloads() {
        EhGalleriesEntity row = gallery(1L, "KOMGA_IMPORT_FAILED");
        row.setTitle("😀".repeat(300));
        row.setOriginalTitle("长".repeat(300));
        row.setKomgaConfirmationReason("确认超时".repeat(100));
        row.setKomgaBookId("b".repeat(200));
        row.setToken("TOKEN_MUST_NOT_LEAK");
        row.setStoragePath("PATH_MUST_NOT_LEAK");
        row.setFilename("FILENAME_MUST_NOT_LEAK");
        row.setGalleryUrl("https://example.test/g/1/TOKEN_MUST_NOT_LEAK/");
        row.setTracePageTrace(Map.of("secret", "TRACE_MUST_NOT_LEAK"));
        row.setTraceRequestUrlChain("URL_CHAIN_MUST_NOT_LEAK");
        row.setRating(Double.NaN);
        row.setFileSizeMb(Double.POSITIVE_INFINITY);
        String content = rich(List.of(row));
        BatchEmailPayload.Gallery detail = BatchEmailPayload.decode(content).galleries().get(0);

        assertEquals(240, detail.title().codePointCount(0, detail.title().length()));
        assertEquals(240, detail.originalTitle().length());
        assertEquals(360, detail.reason().length());
        assertEquals(128, detail.komgaBookId().length());
        assertNull(detail.rating());
        assertNull(detail.fileSizeMb());
        for (String forbidden : List.of("TOKEN_MUST_NOT_LEAK", "PATH_MUST_NOT_LEAK", "FILENAME_MUST_NOT_LEAK",
                "TRACE_MUST_NOT_LEAK", "URL_CHAIN_MUST_NOT_LEAK", "NaN", "Infinity")) {
            assertFalse(content.contains(forbidden), forbidden);
        }
        assertEquals("😀".repeat(300), row.getTitle());
    }

    @Test
    void onlyIncludesReasonsRelevantToTheCurrentStageAndNeverInventsDownloadFailures() {
        EhGalleriesEntity failed = gallery(1L, "DOWNLOAD_FAILED");
        failed.setKomgaConfirmationReason("上次入库留下的原因");
        failed.setDedupeMatchReason("上次去重留下的原因");
        failed.setDuplicateOfGid(99L);
        EhGalleriesEntity review = gallery(2L, "REVIEW_REQUIRED");
        review.setDedupeMatchReason("标题接近，需人工确认");
        review.setKomgaConfirmationReason("上次入库留下的原因");
        EhGalleriesEntity waiting = gallery(3L, "WAITING_KOMGA");
        waiting.setKomgaConfirmationReason("等待图库扫描");
        List<BatchEmailPayload.Gallery> details = BatchEmailPayload.decode(rich(List.of(failed, review, waiting))).galleries();
        assertNull(details.get(0).reason());
        assertEquals("标题接近，需人工确认", details.get(1).reason());
        assertEquals("等待图库扫描", details.get(2).reason());
    }

    @Test
    void payloadTakesImmutableCollectionCopiesAndRejectsMalformedFormats() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("已入库", 1);
        List<BatchEmailPayload.Gallery> details = new ArrayList<>();
        BatchEmailPayload payload = new BatchEmailPayload("抓取流程", 1, 1, 1, false,
                1_000, 2_000, "wf", "run", counts, 1, 1, details);
        counts.put("已入库", 999);
        details.add(new BatchEmailPayload.Gallery(1L, "标题", null, "已入库", 20, 4D, 50D, null, 1, null, "book"));
        assertEquals(1, payload.statusCounts().get("已入库"));
        assertTrue(payload.galleries().isEmpty());
        assertEquals(payload, BatchEmailPayload.decode(payload.encode()));
        assertThrows(UnsupportedOperationException.class, () -> payload.statusCounts().put("已入库", 2));
        assertThrows(IllegalArgumentException.class, () -> BatchEmailPayload.decode("旧通知正文"));
        assertThrows(IllegalArgumentException.class, () -> BatchEmailPayload.decode(BatchEmailPayload.PREFIX + "{"));
        assertThrows(IllegalArgumentException.class, () -> BatchEmailPayload.decode(BatchEmailPayload.PREFIX + "null"));
    }

    private String rich(List<EhGalleriesEntity> rows) {
        return WorkflowSteps.buildRichBatchNotificationContent("抓取流程", 60, 50, 47,
                true, rows, 1_000, 11_000, "workflow-safe", "run-safe");
    }

    private EhGalleriesEntity gallery(Long gid, String status) {
        EhGalleriesEntity row = new EhGalleriesEntity();
        row.setGid(gid);
        row.setTitle("画廊 " + gid);
        row.setDownloadStatus(status);
        row.setPageCount(20);
        row.setRating(4.5D);
        row.setFileSizeMb(120D);
        return row;
    }
}
