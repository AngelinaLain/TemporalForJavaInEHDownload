package com.checker.temporalServices.activities.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按物理文件名和 GID 筛选 Komga 候选，不把展示标题相近直接当成同一本书。
 */
final class KomgaBookMatcher {
    private KomgaBookMatcher() {
    }

    /** 先核对目标图库与系列，再收集物理文件身份匹配的所有书籍 ID，保留多候选供调用方判定歧义。 */
    static List<String> exactBookIds(JSONArray content, Long gid, String expectedFilename,
                                     String expectedSeriesId, String expectedLibraryId) {
        List<String> matches = new ArrayList<>();
        if (content == null || gid == null) return matches;

        for (int i = 0; i < content.size(); i++) {
            JSONObject book = content.getJSONObject(i);
            String bookId = book.getStr("id");
            if (StrUtil.isBlank(bookId)) continue;
            if (!matchesOptionalIdentity(book.getStr("seriesId"), expectedSeriesId)) continue;
            if (!matchesOptionalIdentity(book.getStr("libraryId"), expectedLibraryId)) continue;
            if (matchesFilename(book, gid, expectedFilename)) {
                matches.add(bookId);
            }
        }
        return matches;
    }

    /** 优先比完整文件名或去扩展名的名称；旧数据可按物理路径中的精确 GID 前缀匹配。 */
    static boolean matchesFilename(JSONObject book, Long gid, String expectedFilename) {
        List<String> candidates = List.of(
                StrUtil.blankToDefault(book.getStr("name"), ""),
                StrUtil.blankToDefault(book.getStr("fileName"), ""),
                StrUtil.blankToDefault(book.getStr("url"), "")
        );
        String expected = basename(expectedFilename);
        if (!expected.isBlank()) {
            String expectedStem = stripArchiveExtension(expected);
            boolean expectedFilenameMatched = candidates.stream()
                    .map(KomgaBookMatcher::basename)
                    .anyMatch(candidate -> candidate.equalsIgnoreCase(expected)
                            || stripArchiveExtension(candidate).equalsIgnoreCase(expectedStem));
            if (expectedFilenameMatched) return true;
        }

        // 兼容历史 Download Station 数据：数据库中可能仍是下载任务原名，文件后来才被
        // 重命名为 [gid] xxx.cbz，且旧包没有 ComicInfo.xml。这里仅检查 Komga 返回的
        // 物理 name/url，不检查 metadata.title，避免标题相似导致误命中。
        String gidPrefix = "[" + gid + "] ";
        return candidates.stream()
                .map(KomgaBookMatcher::basename)
                .anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT)
                        .startsWith(gidPrefix.toLowerCase(Locale.ROOT)));
    }

    /** 双方身份都有值时必须相同；缺值时保留历史接口的兼容行为。 */
    private static boolean matchesOptionalIdentity(String actual, String expected) {
        return StrUtil.isBlank(actual) || StrUtil.isBlank(expected) || actual.equals(expected);
    }

    /** 解码路径并取最后一段文件名，兼容 Komga 返回的文件地址与普通名称。 */
    private static String basename(String value) {
        if (value == null || value.isBlank()) return "";
        String decoded;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            decoded = value;
        }
        String normalized = decoded.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
    }

    /** 忽略归档扩展名差异，比较同一物理文件的主体名称。 */
    private static String stripArchiveExtension(String value) {
        return value.replaceFirst("(?i)\\.(zip|cbz|rar)$", "");
    }
}
