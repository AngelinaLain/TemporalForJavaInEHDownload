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
 * 对 Komga 搜索结果做确定性匹配，避免 fullTextSearch 命中后直接取第一条。
 */
final class KomgaBookMatcher {
    private KomgaBookMatcher() {
    }

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

    static boolean matchesFilename(JSONObject book, Long gid, String expectedFilename) {
        List<String> candidates = List.of(
                StrUtil.blankToDefault(book.getStr("name"), ""),
                StrUtil.blankToDefault(book.getStr("fileName"), ""),
                StrUtil.blankToDefault(book.getStr("url"), "")
        );
        String expected = basename(expectedFilename);
        if (!expected.isBlank()) {
            String expectedStem = stripArchiveExtension(expected);
            return candidates.stream()
                    .map(KomgaBookMatcher::basename)
                    .anyMatch(candidate -> candidate.equalsIgnoreCase(expected)
                            || stripArchiveExtension(candidate).equalsIgnoreCase(expectedStem));
        }

        String gidPrefix = "[" + gid + "] ";
        return candidates.stream()
                .map(KomgaBookMatcher::basename)
                .anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT)
                        .startsWith(gidPrefix.toLowerCase(Locale.ROOT)));
    }

    private static boolean matchesOptionalIdentity(String actual, String expected) {
        return StrUtil.isBlank(actual) || StrUtil.isBlank(expected) || actual.equals(expected);
    }

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

    private static String stripArchiveExtension(String value) {
        return value.replaceFirst("(?i)\\.(zip|cbz|rar)$", "");
    }
}
