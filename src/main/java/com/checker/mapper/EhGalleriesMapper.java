package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.EhGalleriesEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * EhGalleries 数据访问层，继承 MyBatis-Plus BaseMapper 提供通用 CRUD
 */
@Mapper
public interface EhGalleriesMapper extends BaseMapper<EhGalleriesEntity> {

    /**
     * 使用 JSON_TABLE 在数据库侧展开 tags 数组并按命名空间聚合计数，
     * 避免将全部画廊加载到内存后在 Java 侧遍历。
     */
    @Select("SELECT " +
            "  CASE WHEN tag LIKE '%:%' THEN SUBSTRING_INDEX(tag, ':', 1) ELSE 'misc' END AS namespace, " +
            "  COUNT(*) AS cnt " +
            "FROM eh_galleries, " +
            "JSON_TABLE(tags, '$[*]' COLUMNS (tag VARCHAR(200) PATH '$')) AS jt " +
            "WHERE tags IS NOT NULL AND JSON_LENGTH(tags) > 0 " +
            "GROUP BY namespace " +
            "ORDER BY cnt DESC " +
            "LIMIT 20")
    List<Map<String, Object>> countTagNamespaces();
}
