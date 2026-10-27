package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 保存某个 AI 用途使用过的提示词版本，方便追溯任务当时采用的提示词。
 */
@Data
@TableName("ai_prompt_versions")
public class AiPromptVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String useCase;
    private Integer version;
    private String prompt;
    private Boolean isDefault;
    private Date createdAt;
}
