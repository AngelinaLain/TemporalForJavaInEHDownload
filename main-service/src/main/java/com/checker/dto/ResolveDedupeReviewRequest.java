package com.checker.dto;

import lombok.Data;

@Data
public class ResolveDedupeReviewRequest {
    /** MATCH 或 DIFFERENT。 */
    private String decision;
    /** MATCH 时必填，且必须是当前审核记录左右两侧之一。 */
    private Long preferredGid;
}
