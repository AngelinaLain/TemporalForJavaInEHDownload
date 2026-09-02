package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Komga 扫描匹配结果。只有 FOUND 才允许进入元数据更新和 IMPORTED 状态。
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

    public static KomgaBookMatchResult found(String bookId, String reason) {
        return new KomgaBookMatchResult(FOUND, bookId, reason, List.of(bookId));
    }

    public static KomgaBookMatchResult notFound(String reason) {
        return new KomgaBookMatchResult(NOT_FOUND, null, reason, List.of());
    }

    public static KomgaBookMatchResult ambiguous(String reason) {
        return new KomgaBookMatchResult(AMBIGUOUS, null, reason, List.of());
    }

    public static KomgaBookMatchResult ambiguous(String reason, List<String> candidateBookIds) {
        return new KomgaBookMatchResult(AMBIGUOUS, null, reason,
                candidateBookIds == null ? List.of() : List.copyOf(candidateBookIds));
    }
}
