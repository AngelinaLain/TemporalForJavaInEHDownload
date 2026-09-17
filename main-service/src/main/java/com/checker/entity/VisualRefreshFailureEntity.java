package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

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
