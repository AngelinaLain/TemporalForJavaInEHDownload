package com.checker.dto;

import lombok.Data;

/**
 * 接收人工去重结论，以及判为同一作品时决定保留的画廊 GID。
 */
@Data
public class ResolveDedupeReviewRequest {
    /** 可选择同一作品、不同作品或需要保留的独立版本。 */
    private String decision;
    /** MATCH 时必填，且必须是当前审核记录左右两侧之一。 */
    private Long preferredGid;
}
