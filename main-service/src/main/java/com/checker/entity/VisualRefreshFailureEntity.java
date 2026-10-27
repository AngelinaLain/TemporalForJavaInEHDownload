package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 记录历史视觉指纹刷新中失败的画廊和原因，供后续定向重试。
 */
@Data
@TableName("eh_visual_refresh_failures")
public class VisualRefreshFailureEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobId;
    private Long gid;
    private String error;
    private Date createdAt;
}
