package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ai_visual_review_results")
public class AiVisualReviewResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobId;
    private String decision;
    private BigDecimal confidence;
    private BigDecimal embeddingSimilarity;
    private BigDecimal perceptualHashSimilarity;
    private Integer matchedPages;
    private Integer comparedPages;
    private BigDecimal pageOrderConsistency;
    private String reason;
    private Boolean requiresHumanReview;
    private Boolean llmUsed;
    private Boolean imagesTransmitted;
    private Date createdAt;
}
