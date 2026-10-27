package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.GalleryPageEmbeddingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写抽样页的图像特征向量，供视觉复核重复利用已有计算结果。
 */
@Mapper
public interface GalleryPageEmbeddingMapper extends BaseMapper<GalleryPageEmbeddingEntity> {
}
