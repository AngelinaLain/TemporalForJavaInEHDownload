package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 保存两本画廊进行 AI 视觉复核时的任务进度及配置快照，便于恢复和查询结果。
 */
@Data
@TableName("ai_visual_review_jobs")
public class AiVisualReviewJobEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workflowId;
    private Long leftGid;
    private Long rightGid;
    private String status;
    private String analysisMode;
    private Long providerId;
    private String model;
    private Long promptVersionId;
    private Boolean allowImageTransmission;
    private Boolean includeMetadata;
    private Integer samplePageCount;
    private java.math.BigDecimal ambiguousMinSimilarity;
    private java.math.BigDecimal highSimilarity;
    private String lastError;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
    private Date updatedAt;
}
