package com.checker.dao.dedupe;

import com.checker.model.dataobject.dedupe.AiVisualReviewResultEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写 AI 视觉复核的结果和解释，供人工审核查看。
 */
@Mapper
public interface AiVisualReviewResultMapper extends BaseMapper<AiVisualReviewResultEntity> {
}
