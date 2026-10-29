package com.checker.service.mail;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotificationEmailRendererTest {
    private final NotificationEmailRenderer renderer = new NotificationEmailRenderer();

    @Test void rendersSummaryStatusGalleryAndTaskInformationWithNoExternalResources() {
        NotificationEmail email = new NotificationEmail("抓取流程汇总", "自动采集", "warning",
                "本次计划 12 本，其中 1 本需要人工处理。", "2026-10-29 18:22:00（Asia/Shanghai · UTC+08:00）",
                "12 分 38 秒", "collection-123", "run-456", List.of(new NotificationEmail.Metric("已入库", "11 / 12")),
                List.of(new NotificationEmail.StatusCount("已入库", 11, "success"), new NotificationEmail.StatusCount("入库异常", 1, "danger")),
                List.of(new NotificationEmail.Gallery(123L, "示例作品", "Example", "入库异常", "warning", "评分 4.8 · 32 页 · 58 MB",
                        "已上传，Komga 尚未完成识别。", "https://example.com/book/123")), 0,
                List.of("请在入库复核页面确认图书关联。"), List.of(new NotificationEmail.Link("打开系统", "https://example.com/")), null);
        String html = renderer.render(email);
        for (String text : List.of("GalleryImport", "状态分布", "需要留意", "画廊处理明细", "GID 123", "评分 4.8", "32 页",
                "collection-123", "run-456", "UTC+08:00", "12 分 38 秒", "已上传，Komga 尚未完成识别。")) {
            assertTrue(html.contains(text), text);
        }
        assertTrue(html.contains("role=\"presentation\""));
        assertTrue(html.contains("@media only screen"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("display:flex"));
        assertFalse(html.contains("display:grid"));
        assertFalse(html.contains("<link"));
    }

    @Test void escapesEveryDynamicTextAndUrlAttributeWithoutInterpretingHtml() {
        String attack = "<img src=x onerror=\"run()\">&'";
        NotificationEmail email = new NotificationEmail(attack, attack, "danger\" style=\"color:red", attack, attack, attack,
                attack, attack, List.of(new NotificationEmail.Metric(attack, attack)),
                List.of(new NotificationEmail.StatusCount(attack, 1, attack)),
                List.of(new NotificationEmail.Gallery(1L, attack, attack + "original", attack, attack, attack, attack,
                        "https://example.com/?a=1&b=2")), 0, List.of(attack),
                List.of(new NotificationEmail.Link(attack, "https://example.com/?x=1&y=2")), attack + "\n第二行");
        String html = renderer.render(email);
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("onerror=\"run()\""));
        assertFalse(html.contains("danger\" style="));
        assertTrue(html.contains("&lt;img src=x onerror=&quot;run()&quot;&gt;&amp;&#39;"));
        assertTrue(html.contains("href=\"https://example.com/?a=1&amp;b=2\""));
        assertTrue(html.contains("href=\"https://example.com/?x=1&amp;y=2\""));
        assertTrue(html.contains("<br>第二行"));
        assertTrue(html.contains("系统通知"));
    }

    @Test void rejectsUnsafeRelativeAndCredentialUrlsInButtonsAndGalleryLinks() {
        for (String url : Arrays.asList("javascript:alert(1)", "data:text/html,test", "file:///tmp/test", "ftp://example.com",
                "//example.com", "/admin", "https://user:pass@example.com/", "https://example.com:99999/", "https://example.com/\nheader", "", null)) {
            NotificationEmail email = new NotificationEmail("通知", null, "info", null, null, null, null, null,
                    null, null, List.of(new NotificationEmail.Gallery(1L, "作品", null, null, null, null, null, url)), 0,
                    null, List.of(new NotificationEmail.Link("打开系统", url)), null);
            assertFalse(renderer.render(email).contains("href="), String.valueOf(url));
        }
    }

    @Test void preservesAllReasonsAndMakesOmittedRowsExplicit() {
        String reason = "第一行\r\n第二行\r第三行\n" + "很长的失败原因".repeat(100);
        NotificationEmail email = new NotificationEmail("异常", null, "danger", null, null, null, null, null,
                null, null, List.of(new NotificationEmail.Gallery(42L, "标题", null, "失败", "danger", null, reason, null)),
                18, List.of("请先修复\n再重试"), null, "已排队\n请稍后查看");
        String html = renderer.render(email);
        assertTrue(html.contains("第一行<br>第二行<br>第三行<br>"));
        assertTrue(html.contains("很长的失败原因".repeat(100)));
        assertTrue(html.contains("另有 18 条明细未在邮件展开"));
        assertTrue(html.contains("请先修复<br>再重试"));
        assertTrue(html.contains("已排队<br>请稍后查看"));
    }

    @Test void nullAndEmptyValuesDoNotExposeNullTextOrEmptyContentSections() {
        NotificationEmail empty = new NotificationEmail(null, null, null, null, null, null, null, null,
                null, null, null, -7, null, null, null);
        String html = renderer.render(empty);
        assertTrue(html.contains("系统通知"));
        assertFalse(html.contains("null"));
        assertFalse(html.contains("状态分布"));
        assertFalse(html.contains("需要留意"));
        assertFalse(html.contains("画廊处理明细"));
        assertFalse(html.contains("补充说明"));
        assertFalse(html.contains("工作流 ID"));
        assertEquals(0, empty.omittedGalleries());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null));
    }

    @Test void copiesAsyncDataAndFiltersNullListEntries() {
        ArrayList<NotificationEmail.Metric> input = new ArrayList<>();
        input.add(new NotificationEmail.Metric("成功", "10"));
        input.add(null);
        NotificationEmail email = new NotificationEmail("通知", null, null, null, null, null, null, null,
                input, null, null, 0, Arrays.asList(null, "", "   "), null, null);
        input.clear();
        assertEquals(1, email.metrics().size());
        assertThrows(UnsupportedOperationException.class, () -> email.metrics().clear());
        String html = renderer.render(email);
        assertTrue(html.contains("成功"));
        assertFalse(html.contains("需要留意"));
    }
}
