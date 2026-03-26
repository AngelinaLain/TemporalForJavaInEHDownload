package com.checker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EHentai 搜索选项（对标 JHenTai SearchConfig）
 * 包装所有搜索参数，通过单一对象传递给 Workflow/Activity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchOptions {
    
    // 基本搜索参数
    @NotBlank(message = "keyword 不能为空")
    private String keyword;              // 搜索关键词
    @Default
    private Integer fCats = 0;           // 分类排除码 (f_cats)
    
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
