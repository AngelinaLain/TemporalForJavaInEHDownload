package com.checker.dao.dedupe;

import com.checker.model.dataobject.dedupe.VisualMatchEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写两本画廊的视觉匹配结果，保存分数、匹配页数和推荐版本。
 */
@Mapper
public interface VisualMatchMapper extends BaseMapper<VisualMatchEntity> {
}
