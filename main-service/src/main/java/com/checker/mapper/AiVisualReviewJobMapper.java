package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.AiVisualReviewJobEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写 AI 视觉复核任务及其进度，供工作流执行和页面查询。
 */
@Mapper
public interface AiVisualReviewJobMapper extends BaseMapper<AiVisualReviewJobEntity> {
}
