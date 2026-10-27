package com.checker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.checker.entity.EhGalleriesEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 负责画廊表的查询与写入，提供列表、统计、批量保存和带并发校验的更新方法。
 */
@Mapper
public interface EhGalleriesMapper extends BaseMapper<EhGalleriesEntity> {

    /** 预览只读取定位归档和 Komga 所需的字段，避免每张图片请求都加载抓取追踪大数据。 */
    @Select("SELECT gid, download_status, komga_book_id, storage_path, filename, updated_at " +
            "FROM eh_galleries WHERE gid = #{gid}")
    EhGalleriesEntity getPreviewMetadata(@Param("gid") long gid);

    /** 读取本次 Komga ID 刷新范围的数量和最大 GID，让后续按固定上界分批处理。 */
    @Select({"<script>", "SELECT COALESCE(MAX(gid),0) maxGid, COUNT(*) total FROM eh_galleries WHERE",
            "<choose><when test='gids != null and !gids.isEmpty()'>gid IN",
            "<foreach collection='gids' item='gid' open='(' separator=',' close=')'>#{gid}</foreach>",
            "</when><otherwise>((komga_book_id IS NOT NULL AND TRIM(komga_book_id) &lt;&gt; '')",
            "OR download_status IN ('IMPORTED','已入库'))</otherwise></choose>", "</script>"})
    Map<String, Object> getKomgaIdRefreshBounds(@Param("gids") List<Long> gids);

    @Select({"<script>", "SELECT gid,title,filename,storage_path,komga_book_id FROM eh_galleries",
            "WHERE gid &gt; #{after} AND gid &lt;= #{maxGid} AND",
            "<choose><when test='gids != null and !gids.isEmpty()'>gid IN",
            "<foreach collection='gids' item='gid' open='(' separator=',' close=')'>#{gid}</foreach>",
            "</when><otherwise>((komga_book_id IS NOT NULL AND TRIM(komga_book_id) &lt;&gt; '')",
            "OR download_status IN ('IMPORTED','已入库'))</otherwise></choose>",
            "ORDER BY gid ASC LIMIT #{size}", "</script>"})
    List<EhGalleriesEntity> getKomgaIdRefreshBatch(@Param("after") long after, @Param("maxGid") long maxGid,
                                                @Param("gids") List<Long> gids, @Param("size") int size);

    /** 只有原 ID、文件名和目录仍与读取时一致才更新，避免覆盖其他任务刚改过的记录。 */
    @Update("UPDATE eh_galleries SET komga_book_id = #{newId}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE gid = #{gid} AND BINARY komga_book_id <=> BINARY #{oldId} " +
            "AND BINARY filename <=> BINARY #{filename} AND BINARY storage_path <=> BINARY #{storagePath}")
    int compareAndSetKomgaBookId(@Param("gid") long gid, @Param("oldId") String oldId,
                                @Param("newId") String newId, @Param("filename") String filename,
                                @Param("storagePath") String storagePath);

    /**
     * 大批量写入：一次 INSERT 多行（由 BatchSqlInjector 注入），
     * 忽略 null 字段与自动填充字段，比逐条 INSERT 性能高一个量级。
     */
    int insertBatchSomeColumn(@Param("list") List<EhGalleriesEntity> entities);

    /** 创建锁桶并通过行锁串行化同一候选组的首选版本决策。 */
    @Insert("INSERT IGNORE INTO eh_dedupe_locks(candidate_key) VALUES(#{candidateKey})")
    int ensureDedupeLock(@Param("candidateKey") String candidateKey);

    @Select("SELECT candidate_key FROM eh_dedupe_locks WHERE candidate_key = #{candidateKey} FOR UPDATE")
    String lockDedupeCandidate(@Param("candidateKey") String candidateKey);

    /**
     * 一次查询获取仪表盘概览，避免按状态逐条 COUNT。
     */
    @Select("SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('DOWNLOADED', '已下载') THEN 1 ELSE 0 END), 0) AS downloaded, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('IMPORTED', '已入库') THEN 1 ELSE 0 END), 0) AS imported, " +
            "COALESCE(SUM(CASE WHEN download_status IN ('DOWNLOAD_FAILED', '下载失败', 'KOMGA_IMPORT_FAILED', 'Komga 入库失败') THEN 1 ELSE 0 END), 0) AS failed, " +
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
            "WHEN '等待 Komga' THEN 'WAITING_KOMGA' " +
            "WHEN '不完整' THEN 'PARTIAL' " +
            "WHEN '下载失败' THEN 'DOWNLOAD_FAILED' " +
            "WHEN 'Komga 入库失败' THEN 'KOMGA_IMPORT_FAILED' " +
            "WHEN '已入库' THEN 'IMPORTED' " +
            "WHEN '待去重审核' THEN 'REVIEW_REQUIRED' " +
            "WHEN '阻断' THEN 'BLOCKED' " +
            "WHEN '已忽略' THEN 'IGNORED' " +
            "ELSE download_status END AS status, COUNT(*) AS cnt " +
            "FROM eh_galleries GROUP BY CASE download_status " +
            "WHEN '未下载' THEN 'PENDING' WHEN '下载中' THEN 'DOWNLOADING' " +
            "WHEN '已下载' THEN 'DOWNLOADED' WHEN '等待 Komga' THEN 'WAITING_KOMGA' " +
            "WHEN '不完整' THEN 'PARTIAL' WHEN '下载失败' THEN 'DOWNLOAD_FAILED' " +
            "WHEN 'Komga 入库失败' THEN 'KOMGA_IMPORT_FAILED' " +
            "WHEN '已入库' THEN 'IMPORTED' WHEN '待去重审核' THEN 'REVIEW_REQUIRED' " +
            "WHEN '阻断' THEN 'BLOCKED' " +
            "WHEN '已忽略' THEN 'IGNORED' ELSE download_status END")
    List<Map<String, Object>> countByDownloadStatus();

    /**
     * Prometheus 下载进度指标所需的聚合值。只聚合当前下载中的记录，
     * 避免为每个画廊创建长期存在的高基数时间序列。
     */
    @Select("SELECT COUNT(*) AS active, " +
            "COALESCE(SUM(downloaded_bytes), 0) AS downloaded_bytes, " +
            "COALESCE(SUM(file_size_mb * 1024 * 1024), 0) AS total_bytes " +
            "FROM eh_galleries " +
            "WHERE download_status IN ('DOWNLOADING', '下载中')")
    Map<String, Object> getDownloadProgressMetrics();

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
