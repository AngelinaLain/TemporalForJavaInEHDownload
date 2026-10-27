package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 保存每种 AI 任务选用的模型、分析方式、图片传输权限和判断阈值。
 */
@Data
@TableName("ai_use_case_configs")
public class AiUseCaseConfigEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String useCase;
    private Long providerId;
    private String model;
    private String analysisMode;
    private Boolean allowImageTransmission;
    private Boolean includeMetadata;
    private Integer samplePageCount;
    private BigDecimal ambiguousMinSimilarity;
    private BigDecimal highSimilarity;
    private Long promptVersionId;
    private Date createdAt;
    private Date updatedAt;
}
