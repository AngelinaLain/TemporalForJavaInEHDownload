package com.checker.mapper;

import com.checker.dto.BatchEmailPayload;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 为旧版单条通知补充少量画廊概况，只查询允许出现在邮件中的字段。 */
@Mapper
public interface NotificationGalleryMapper {
    /** 按明确 GID 一次批量查询，不读取下载令牌、归档路径、请求追踪或完整实体。 */
    @Select("<script>SELECT gid, title, original_title, download_status AS status, page_count, rating, file_size_mb, "
            + "duplicate_of_gid, komga_confirmation_attempts AS confirmation_attempts, "
            + "CASE WHEN download_status IN ('REVIEW_REQUIRED','IGNORED','待去重审核','已忽略') "
            + "THEN dedupe_match_reason WHEN download_status IN ('WAITING_KOMGA','KOMGA_IMPORT_FAILED','DOWNLOADED',"
            + "'等待 Komga','Komga 入库失败','已下载') THEN komga_confirmation_reason ELSE NULL END AS reason, komga_book_id "
            + "FROM eh_galleries WHERE gid IN "
            + "<foreach collection='gids' item='gid' open='(' separator=',' close=')'>#{gid}</foreach> "
            + "ORDER BY gid LIMIT 10</script>")
    @ConstructorArgs({
            @Arg(column = "gid", javaType = Long.class), @Arg(column = "title", javaType = String.class),
            @Arg(column = "original_title", javaType = String.class), @Arg(column = "status", javaType = String.class),
            @Arg(column = "page_count", javaType = Integer.class), @Arg(column = "rating", javaType = Double.class),
            @Arg(column = "file_size_mb", javaType = Double.class), @Arg(column = "duplicate_of_gid", javaType = Long.class),
            @Arg(column = "confirmation_attempts", javaType = Integer.class), @Arg(column = "reason", javaType = String.class),
            @Arg(column = "komga_book_id", javaType = String.class)
    })
    List<BatchEmailPayload.Gallery> findForNotification(@Param("gids") List<Long> gids);
}
