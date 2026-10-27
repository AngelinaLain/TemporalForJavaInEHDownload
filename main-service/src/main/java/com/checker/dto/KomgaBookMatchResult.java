package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表示 Komga 查找书籍的结果，区分唯一找到、未找到和多个候选，防止把不确定结果当成入库成功。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KomgaBookMatchResult {
    public static final String FOUND = "FOUND";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String AMBIGUOUS = "AMBIGUOUS";

    private String status;
    private String bookId;
    private String reason;
    private List<String> candidateBookIds;

    /**
     * 生成唯一找到书籍的结果，携带后续更新元数据所需的书籍 ID。
     */
    public static KomgaBookMatchResult found(String bookId, String reason) {
        return new KomgaBookMatchResult(FOUND, bookId, reason, List.of(bookId));
    }

    /**
     * 生成尚未找到书籍的结果，保留原因，供工作流决定是否继续等待扫描。
     */
    public static KomgaBookMatchResult notFound(String reason) {
        return new KomgaBookMatchResult(NOT_FOUND, null, reason, List.of());
    }

    /**
     * 生成多个候选的结果，阻止自动选取第一本书，并保留可供人工核对的信息。
     */
    public static KomgaBookMatchResult ambiguous(String reason) {
        return new KomgaBookMatchResult(AMBIGUOUS, null, reason, List.of());
    }

    /**
     * 生成多个候选的结果，阻止自动选取第一本书，并保留可供人工核对的信息。
     */
    public static KomgaBookMatchResult ambiguous(String reason, List<String> candidateBookIds) {
        return new KomgaBookMatchResult(AMBIGUOUS, null, reason,
                candidateBookIds == null ? List.of() : List.copyOf(candidateBookIds));
    }
}
