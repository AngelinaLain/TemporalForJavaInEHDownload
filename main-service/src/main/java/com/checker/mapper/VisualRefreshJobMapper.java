package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.VisualRefreshJobEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写历史视觉指纹刷新任务的进度，使任务结果可以持久查询。
 */
@Mapper
public interface VisualRefreshJobMapper extends BaseMapper<VisualRefreshJobEntity> {
}
