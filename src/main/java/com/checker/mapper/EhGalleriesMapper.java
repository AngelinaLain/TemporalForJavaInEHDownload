package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.EhGalleriesEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * EhGalleries 数据访问层，继承 MyBatis-Plus BaseMapper 提供通用 CRUD
 */
@Mapper
public interface EhGalleriesMapper extends BaseMapper<EhGalleriesEntity> {
}
