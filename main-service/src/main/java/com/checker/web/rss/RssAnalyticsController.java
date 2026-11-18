package com.checker.web.rss;

import com.checker.service.rss.RssAnalyticsService;
import com.checker.shared.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据概览中的 RSS 维度：订阅发现、入库、匹配既有画廊与合集归位的统计。
 */
@RestController
@RequestMapping("/api/rss/analytics")
@Tag(name = "RSS 订阅分析", description = "RSS 发现、入库、匹配与合集归位统计")
@PreAuthorize("hasRole('ADMIN')")
public class RssAnalyticsController {

    private final RssAnalyticsService analyticsService;

    public RssAnalyticsController(RssAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping
    public Result<Map<String, Object>> analytics() {
        return Result.success(analyticsService.analyze());
    }
}
