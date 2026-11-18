package com.checker.dao.ai;

import com.checker.model.dataobject.ai.AiPromptVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写 AI 提示词版本记录，供配置管理与任务追溯使用。
 */
@Mapper
public interface AiPromptVersionMapper extends BaseMapper<AiPromptVersionEntity> {
}
