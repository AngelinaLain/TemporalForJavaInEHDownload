package com.checker.dao.collection;

import com.checker.model.dataobject.collection.GalleryCollectionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写本地画廊合集的基本信息，供合集管理服务使用。
 */
@Mapper
public interface GalleryCollectionMapper extends BaseMapper<GalleryCollectionEntity> {
}
