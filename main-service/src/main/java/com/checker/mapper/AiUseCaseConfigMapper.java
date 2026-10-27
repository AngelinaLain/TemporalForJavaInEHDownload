package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.AiUseCaseConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写各种 AI 用途的策略配置，供运行任务时选择模型与分析方式。
 */
@Mapper
public interface AiUseCaseConfigMapper extends BaseMapper<AiUseCaseConfigEntity> {
}
