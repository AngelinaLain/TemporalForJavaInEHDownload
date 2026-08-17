package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.EhGalleriesEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * EhGalleries 数据访问层，继承 MyBatis-Plus BaseMapper 提供通用 CRUD
 */
@Mapper
public interface EhGalleriesMapper extends BaseMapper<EhGalleriesEntity> {

    /**
     * 大批量写入：一次 INSERT 多行（由 BatchSqlInjector 注入），
     * 忽略 null 字段与自动填充字段，比逐条 INSERT 性能高一个量级。
     */
    int insertBatchSomeColumn(@Param("list") List<EhGalleriesEntity> entities);

    /**
     * 一次查询获取仪表盘概览，避免按状态逐条 COUNT。
     */
    @Select("SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('DOWNLOADED', '已下载') THEN 1 ELSE 0 END), 0) AS downloaded, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('IMPORTED', '已入库') THEN 1 ELSE 0 END), 0) AS imported, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('DOWNLOAD_FAILED', '下载失败') THEN 1 ELSE 0 END), 0) AS failed, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('PENDING', '未下载') THEN 1 ELSE 0 END), 0) AS pending, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('PARTIAL', '不完整') THEN 1 ELSE 0 END), 0) AS partial, " +
            "COALESCE(SUM(file_size_mb), 0) AS total_size_mb " +
            "FROM eh_galleries")
    Map<String, Object> getDashboardOverview();

    /**
     * 数据库侧按下载状态分组，避免枚举状态时产生 N 次 COUNT 查询。
     */
    @Select("SELECT CASE download_status " +
            "WHEN '未下载' THEN 'PENDING' " +
            "WHEN '下载中' THEN 'DOWNLOADING' " +
            "WHEN '已下载' THEN 'DOWNLOADED' " +
            "WHEN '不完整' THEN 'PARTIAL' " +
            "WHEN '下载失败' THEN 'DOWNLOAD_FAILED' " +
            "WHEN '已入库' THEN 'IMPORTED' " +
            "WHEN '阻断' THEN 'BLOCKED' " +
            "WHEN '已忽略' THEN 'IGNORED' " +
            "ELSE download_status END AS status, COUNT(*) AS cnt " +
            "FROM eh_galleries GROUP BY CASE download_status " +
            "WHEN '未下载' THEN 'PENDING' WHEN '下载中' THEN 'DOWNLOADING' " +
            "WHEN '已下载' THEN 'DOWNLOADED' WHEN '不完整' THEN 'PARTIAL' " +
            "WHEN '下载失败' THEN 'DOWNLOAD_FAILED' " +
            "WHEN '已入库' THEN 'IMPORTED' WHEN '阻断' THEN 'BLOCKED' " +
            "WHEN '已忽略' THEN 'IGNORED' ELSE download_status END")
    List<Map<String, Object>> countByDownloadStatus();

    /**
     * 数据库侧计算文件大小分布，避免加载所有画廊实体到 JVM。
     */
    @Select("SELECT " +
            "COALESCE(SUM(CASE WHEN file_size_mb > 0 AND file_size_mb < 50 THEN 1 ELSE 0 END), 0) AS lt_50, " +
            "COALESCE(SUM(CASE WHEN file_size_mb >= 50 AND file_size_mb < 100 THEN 1 ELSE 0 END), 0) AS from_50_to_100, " +
            "COALESCE(SUM(CASE WHEN file_size_mb >= 100 AND file_size_mb < 200 THEN 1 ELSE 0 END), 0) AS from_100_to_200, " +
            "COALESCE(SUM(CASE WHEN file_size_mb >= 200 AND file_size_mb < 500 THEN 1 ELSE 0 END), 0) AS from_200_to_500, " +
            "COALESCE(SUM(CASE WHEN file_size_mb >= 500 AND file_size_mb < 1024 THEN 1 ELSE 0 END), 0) AS from_500_to_1024, " +
            "COALESCE(SUM(CASE WHEN file_size_mb >= 1024 THEN 1 ELSE 0 END), 0) AS ge_1024 " +
            "FROM eh_galleries")
    Map<String, Object> getFileSizeBuckets();

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
