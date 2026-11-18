package com.checker.dao.dedupe;

import com.checker.model.dataobject.dedupe.GalleryPageEmbeddingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写抽样页的图像特征向量，供视觉复核重复利用已有计算结果。
 */
@Mapper
public interface GalleryPageEmbeddingMapper extends BaseMapper<GalleryPageEmbeddingEntity> {
}
