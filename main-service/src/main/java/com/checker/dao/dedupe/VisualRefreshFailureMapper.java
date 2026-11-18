package com.checker.dao.dedupe;

import com.checker.model.dataobject.dedupe.VisualRefreshFailureEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写视觉指纹批量刷新时的失败记录，供定向重试和错误展示。
 */
@Mapper
public interface VisualRefreshFailureMapper extends BaseMapper<VisualRefreshFailureEntity> {
}
