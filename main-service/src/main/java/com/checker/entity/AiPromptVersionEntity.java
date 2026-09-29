package com.checker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

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
