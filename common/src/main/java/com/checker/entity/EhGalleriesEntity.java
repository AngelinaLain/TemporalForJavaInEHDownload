package com.checker.entity;


import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * EHentai画廊抓取队列
 */
@Data // 自动生成 get/set/toString
@TableName(value = "eh_galleries", autoResultMap = true)
public class EhGalleriesEntity implements Serializable {

    /**
     * 画廊ID，唯一
     */
    @TableId(value = "gid", type = IdType.INPUT)
    private Long gid;

    /**
     * 画廊Token
     */
    private String token;

    /**
     * 原始完整标题
     */
    private String title;

    /**
     * EH gdata 返回的原始标题（通常为日文），用于跨汉化版本归组。
     */
    private String originalTitle;

    /** EH gdata 返回的页数。 */
    private Integer pageCount;

    /** EH gdata 返回的社区评分。 */
    private Double rating;

    /**
     * 基于原始标题与核心标签计算的 SHA-256 作品指纹。
     * 为空表示元数据不足，不能安全地自动去重。
     */
    private String dedupeKey;

    /**
     * 同一作品中被选为首选版本的 GID；为空代表该记录是首选版本。
     */
    private Long duplicateOfGid;

    /** 自动归组的置信度（0-100）。 */
    private Integer dedupeConfidence;

    /**
     * 清理后的安全文件名
     */
    private String filename;

    /**
     * 画廊直达链接
     */
    private String galleryUrl;

    /**
     * 使用的搜索关键词
     */
    private String searchQuery;

    /**
     * 抓取时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date crawledAt;

    /**
     * 下载任务状态
     */
    private String downloadStatus;

    /**
     * 抓取该画廊时爬取的总页数
     */
    @TableField("_trace_pages_crawled")
    private Integer tracePagesCrawled;

    /**
     * 爬虫停止的原因
     */
    @TableField("_trace_stop_reason")
    private String traceStopReason;

    /**
     * 最后的翻页游标
     */
    @TableField("_trace_last_next_cursor")
    private String traceLastNextCursor;

    /**
     * 请求URL链条
     */
    @TableField("_trace_request_url_chain")
    private String traceRequestUrlChain;

    /**
     * 网页首页Title
     */
    @TableField("_trace_first_page_title")
    private String traceFirstPageTitle;

    /**
     * 单页详细抓取追踪 (JSON格式)
     */
    @TableField(value = "_trace_page_trace", typeHandler = JacksonTypeHandler.class)
    private Object tracePageTrace;

    @TableField(value = "tags", typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * Komga 中的书籍 ID (用于记录是否成功推送到 Komga)
     */
    private String komgaBookId;

    /**
     * 文件大小
     */
    private Double fileSizeMb;

    /**
     * 本地下载已完成的字节数（用于前端展示下载进度）。
     */
    private Long downloadedBytes;

    /**
     * AI 生成的内容概述（中文，约 150 字）
     */
    private String summary;

    /**
     * 记录最后更新时间，供 Komga 增量同步（时间戳比对）使用。
     * 由 MetaObjectHandler 自动填充，数据库侧亦有 ON UPDATE CURRENT_TIMESTAMP 兜底。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updatedAt;
}
