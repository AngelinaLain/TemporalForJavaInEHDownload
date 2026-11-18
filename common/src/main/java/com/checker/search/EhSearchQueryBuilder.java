package com.checker.search;

import com.checker.dto.SearchOptions;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Shared by interactive searches, RSSHub routes and recovery scans. */
public final class EhSearchQueryBuilder {
    private EhSearchQueryBuilder() {}

    public static String query(SearchOptions options) {
        String keyword = options.getKeyword() == null ? "" : options.getKeyword().trim();
        if (options.getLanguage() != null && !options.getLanguage().isBlank()) {
            keyword += " language:\"" + options.getLanguage().trim() + "\"";
        }
        StringBuilder query = new StringBuilder("f_search=").append(encode(keyword.trim()))
                .append("&f_cats=").append(options.getFilterCats() == null ? 0 : options.getFilterCats())
                .append("&advsearch=1");
        if (options.getMinimumRating() != null && options.getMinimumRating() > 1) query.append("&f_srdd=").append(options.getMinimumRating());
        if (options.getPageAtLeast() != null && options.getPageAtLeast() > 0) query.append("&f_spf=").append(options.getPageAtLeast());
        if (options.getPageAtMost() != null && options.getPageAtMost() > 0) query.append("&f_spt=").append(options.getPageAtMost());
        flag(query, "f_sh", options.getSearchExpungedGalleries());
        flag(query, "f_sto", options.getShowOnlyWithTorrents());
        flag(query, "f_sfl", options.getDisableLanguageFilter());
        flag(query, "f_sfu", options.getDisableUploaderFilter());
        flag(query, "f_sft", options.getDisableTagsFilter());
        return query.toString();
    }

    public static String searchUrl(SearchOptions options) { return "https://e-hentai.org/?" + query(options); }

    public static String rssRoute(SearchOptions options) {
        // The entire source query is one route segment; % inside its values needs another encoding layer.
        return "/ehentai/search/" + encode(query(options)) + "/0/bittorrent=false&embed_thumb=false";
    }

    public static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static void flag(StringBuilder query, String name, Boolean enabled) {
        if (Boolean.TRUE.equals(enabled)) query.append('&').append(name).append("=on");
    }
}
