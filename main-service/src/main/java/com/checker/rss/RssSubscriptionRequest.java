package com.checker.rss;

import com.checker.dto.SearchOptions;
import lombok.Data;
import java.util.List;

@Data
public class RssSubscriptionRequest {
    private String name;
    private String keyword = "";
    private List<String> tags = List.of();
    private SearchOptions searchOptions = new SearchOptions();
    private int intervalMinutes = 15;
    private boolean enabled = true;
    private boolean autoDownload = true;
    private String initialMode = "NEW_ONLY";
    private int historyDays = 7;
    private int backfillIntervalHours = 6;
    private int maxBackfillPages = 10;

    public void validate() {
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("订阅名称需为 1–100 个字符");
        name = name.trim();
        keyword = keyword == null ? "" : keyword.trim();
        tags = tags == null ? List.of() : tags.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        String merged = String.join(" ", tags) + (keyword.isEmpty() ? "" : " " + keyword);
        if (merged.isBlank() || merged.length() > 2000) throw new IllegalArgumentException("请填写关键词或标签，检索表达式最长 2000 字符");
        if (searchOptions == null) searchOptions = new SearchOptions();
        searchOptions.setKeyword(merged.trim());
        searchOptions.setGalleryUrls(null);
        if (intervalMinutes < 5 || intervalMinutes > 1440) throw new IllegalArgumentException("检查间隔应为 5–1440 分钟");
        if (maxBackfillPages < 1 || maxBackfillPages > 100 || backfillIntervalHours < 1 || backfillIntervalHours > 168) throw new IllegalArgumentException("补查页数应为 1–100，间隔应为 1–168 小时");
        if (!List.of("NEW_ONLY", "CURRENT").contains(initialMode) || historyDays < 1 || historyDays > 365) throw new IllegalArgumentException("首次策略无效；历史范围应为 1–365 天");
        Integer cats = searchOptions.getFilterCats(), rating = searchOptions.getMinimumRating();
        if (cats != null && (cats < 0 || cats > 1023) || rating != null && (rating < 1 || rating > 5)) throw new IllegalArgumentException("分类或评分参数无效");
        Integer min = searchOptions.getPageAtLeast(), max = searchOptions.getPageAtMost();
        if (min != null && min < 0 || max != null && max < 0 || min != null && max != null && max > 0 && min > max) throw new IllegalArgumentException("页数范围无效");
        if (searchOptions.getLanguage() != null && !List.of("", "chinese", "japanese", "english").contains(searchOptions.getLanguage())) throw new IllegalArgumentException("语言参数无效");
    }
}
