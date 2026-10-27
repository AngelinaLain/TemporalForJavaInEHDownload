package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.GalleryPageHashEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 读写抽样页的感知哈希和画质数据，供画廊视觉匹配使用。
 */
@Mapper
public interface GalleryPageHashMapper extends BaseMapper<GalleryPageHashEntity> {
}
