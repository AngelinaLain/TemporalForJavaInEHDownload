package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 保存两本画廊按抽样页面比较后的相似度、匹配页数和推荐版本。
 */
@Data
@TableName("eh_visual_matches")
public class VisualMatchEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long leftGid;
    private Long rightGid;
    private Integer similarity;
    private Integer matchedPages;
    private Integer leftSamples;
    private Integer rightSamples;
    private Integer sampleCoverage;
    private Integer orderConsistency;
    /** 证据不足时必须把旧推荐清为空，不能被默认的“空值不更新”策略跳过。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long recommendedGid;
    private Integer qualityDelta;
    private String reason;
    private Integer algorithmVersion;
    private Date createdAt;
    private Date updatedAt;
}
