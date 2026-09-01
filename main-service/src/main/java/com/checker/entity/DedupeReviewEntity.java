package com.checker.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("eh_dedupe_reviews")
public class DedupeReviewEntity implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String candidateKey;
    private Long leftGid;
    private Long rightGid;
    private Integer matchScore;
    private String matchReason;
    private Long recommendedGid;
    private String decision;
    private Long preferredGid;
    private String reviewedBy;
    private Date reviewedAt;
    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updatedAt;
}
