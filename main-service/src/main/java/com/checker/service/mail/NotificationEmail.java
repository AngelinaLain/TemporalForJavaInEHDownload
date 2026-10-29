package com.checker.service.mail;

import java.util.List;

/** 保存邮件展示需要的安全数据，不携带下载令牌、服务凭据或整个画廊实体。 */
public record NotificationEmail(String subject, String category, String level, String summary,
                                String generatedAt, String duration, String workflowId, String runId,
                                List<Metric> metrics, List<StatusCount> statuses, List<Gallery> galleries,
                                int omittedGalleries, List<String> notices, List<Link> links, String detail) {
    public NotificationEmail {
        metrics = immutable(metrics);
        statuses = immutable(statuses);
        galleries = immutable(galleries);
        notices = immutable(notices);
        links = immutable(links);
        omittedGalleries = Math.max(0, omittedGalleries);
    }

    /** 忽略空元素并复制集合，避免异步发送时原始列表被其他线程修改。 */
    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null).toList();
    }

    public record Metric(String label, String value) { }
    public record StatusCount(String label, int count, String level) { }
    public record Gallery(Long gid, String title, String originalTitle, String status, String level,
                          String facts, String reason, String url) { }
    public record Link(String label, String url) { }
}
