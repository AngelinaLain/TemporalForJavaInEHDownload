package com.checker.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Date;

/**
 * Komga 入库复核列表项，不暴露画廊 token 等敏感字段。
 */
@Value
@Builder
public class KomgaImportReviewItem {
    Long gid;
    String title;
    String filename;
    String galleryUrl;
    String downloadStatus;
    String komgaBookId;
    Integer confirmationAttempts;
    Date lastConfirmationAt;
    String confirmationReason;
    String candidateBookIds;
}
