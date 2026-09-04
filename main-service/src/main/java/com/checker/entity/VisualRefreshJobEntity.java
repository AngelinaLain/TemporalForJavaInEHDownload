package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("eh_visual_refresh_jobs")
public class VisualRefreshJobEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String status;
    private Boolean forceRefresh;
    private Integer algorithmVersion;
    private Integer total;
    private Integer processed;
    private Integer succeeded;
    private Integer failed;
    private Long currentGid;
    private String lastError;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
    private Date updatedAt;
}
