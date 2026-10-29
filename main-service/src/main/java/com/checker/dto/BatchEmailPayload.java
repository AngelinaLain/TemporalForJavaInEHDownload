package com.checker.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存一封批次汇总邮件需要的有限信息，不把画廊 token、归档路径或抓取追踪传入通知。
 * 带版本前缀的 JSON 仍通过原来的字符串活动参数传递，旧版纯文本通知继续兼容。
 */
public record BatchEmailPayload(String flowName, int discovered, int planned, int started,
                                boolean stoppedByFatalError, long startedAt, long finishedAt,
                                String workflowId, String runId, Map<String, Integer> statusCounts,
                                int totalGalleries, int omittedGalleries, List<Gallery> galleries) {
    public static final String PREFIX = "GI_NOTIFICATION_V1:";
    private static final ObjectMapper JSON = new ObjectMapper();

    public BatchEmailPayload {
        // 固定字段顺序和集合内容，调用方后续修改原集合不会改变已经生成的报告。
        statusCounts = statusCounts == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(statusCounts));
        galleries = galleries == null ? List.of() : List.copyOf(galleries);
    }

    /** 将本轮已确定的数据编码为通知正文，不读取数据库、网络或本机时间。 */
    public String encode() {
        try {
            return PREFIX + JSON.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("批次邮件内容编码失败", exception);
        }
    }

    /** 解码新版汇总正文；调用方可在内容为旧文本时沿用普通通知模板。 */
    public static BatchEmailPayload decode(String content) {
        if (content == null || !content.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不是新版批次邮件内容");
        }
        try {
            BatchEmailPayload payload = JSON.readValue(content.substring(PREFIX.length()), BatchEmailPayload.class);
            if (payload == null) throw new IllegalArgumentException("批次邮件内容不能为空");
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("批次邮件内容格式不正确", exception);
        }
    }

    /** 只保留邮件展示所需的画廊概况；完整统计不受最多展示三十条明细的限制。 */
    public record Gallery(Long gid, String title, String originalTitle, String status,
                          Integer pageCount, Double rating, Double fileSizeMb, Long duplicateOfGid,
                          Integer confirmationAttempts, String reason, String komgaBookId) {
    }
}
