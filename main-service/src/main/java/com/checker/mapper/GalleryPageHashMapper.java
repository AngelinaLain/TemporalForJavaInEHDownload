package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.GalleryPageHashEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 读写抽样页的感知哈希和画质数据，供画廊视觉匹配使用。
 */
@Mapper
public interface GalleryPageHashMapper extends BaseMapper<GalleryPageHashEntity> {
    /** 在数据库完成去重计数，避免把所有画廊编号拉进内存。 */
    @Select("SELECT COUNT(DISTINCT gid) FROM eh_gallery_page_hashes WHERE algorithm_version = #{version}")
    long countCurrentGalleries(@Param("version") int version);
}
