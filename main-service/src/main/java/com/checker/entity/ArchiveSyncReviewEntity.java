package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "eh_archive_sync_reviews", autoResultMap = true)
public class ArchiveSyncReviewEntity {
    @TableId(type = IdType.INPUT)
    private Long gid;
    private String title;
    private String expectedFilename;
    private String selectedFilename;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> candidateFilenames;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Integer> coverScores;
    private String coverStatus;
    private String coverMessage;
    private Date coverCheckedAt;
    private String matchType;
    private String status;
    private String message;
    private Date createdAt;
    private Date updatedAt;
}
