package com.checker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 把关键词、标签分类、评分、页数和指定画廊链接等采集条件一起传给工作流和爬虫。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchOptions {
    
    // 基本搜索参数
    private String keyword;              // 搜索关键词

    /**
     * 指定画廊链接；每项必须带画廊 token，例如 https://e-hentai.org/g/123/token/。
     */
    private List<String> galleryUrls;

    @Default
    private Integer filterCats = 0;           // 分类排除码 (f_cats)

    // 星级过滤
    @Default
    private Integer minimumRating = 1;   // 最低星级 (f_srdd), 1=不过滤
    
    // 语言过滤
    private String language;             // 语言代码，如 "chinese", "japanese"
    
    // 页数范围过滤
    private Integer pageAtLeast;         // 最少页数 (f_spf)
    private Integer pageAtMost;          // 最多页数 (f_spt)
    
    // 高级搜索选项
    @Default
    private Boolean searchExpungedGalleries = false;  // 搜索已删除的画廊 (f_sh)
    @Default
    private Boolean showOnlyWithTorrents = false;     // 仅显示有种子的 (f_sto)
    
    // 禁用过滤器
    @Default
    private Boolean disableLanguageFilter = false;    // 禁用语言过滤 (f_sfl)
    @Default
    private Boolean disableUploaderFilter = false;    // 禁用上传者过滤 (f_sfu)
    @Default
    private Boolean disableTagsFilter = false;        // 禁用标签过滤 (f_sft)
}
