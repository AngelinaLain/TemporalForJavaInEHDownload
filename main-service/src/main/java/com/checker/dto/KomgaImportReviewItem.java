package com.checker.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Date;

/**
 * 返回 Komga 入库复核列表所需的状态、关联和确认记录，不携带画廊访问凭据。
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
