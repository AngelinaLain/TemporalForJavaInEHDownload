package com.checker.common;

import com.checker.entity.EhGalleriesEntity;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * EH 标题约定下的系列识别评分。系列与“重复版本”是两个概念：本类会去掉卷号，
 * 但保留作品主体，并把作者、原作标签和封面指纹仅作为辅助信号。
 */
public final class GallerySeriesMatching {
    private static final Pattern LEADING_EVENT_OR_CREDIT = Pattern.compile(
            "^(?:\\s*[\\[【(（][^\\]】)）]{1,80}[\\]】)）]\\s*)+");
    private static final Pattern TRANSLATION_BLOCK = Pattern.compile(
            "(?i)[\\[【(（][^\\]】)）]*(?:chinese|english|中文|中國翻譯|中国翻译|中国翻訳|漢化|汉化|翻译|翻譯|無修正|无修正|digital|カラー)[^\\]】)）]*[\\]】)）]");
    private static final Pattern VOLUME_MARKER = Pattern.compile(
            "(?iu)(?:\\b(?:vol(?:ume)?|ch(?:apter)?|part|episode|ep|book|season)\\.?\\s*[-_:]?\\s*(?:\\d+(?:\\.\\d+)?|[ivxlcdm]+)\\b|第\\s*[0-9０-９一二三四五六七八九十百]+\\s*[巻卷話话章节集部]|(?:その|其)\\s*[0-9０-９一二三四五六七八九十百]+)");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?iu)(?:[-_:# ]+(?:\\d+(?:\\.\\d+)?|[ivxlcdm]+))$");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsLatin}\\p{IsDigit}]+");
    private static final Set<String> SUPPORT_NAMESPACES = Set.of("artist", "group", "parody", "character");

    private GallerySeriesMatching() {
    }

    public static SeriesMatch score(EhGalleriesEntity left, String leftCoverHash,
                                    EhGalleriesEntity right, String rightCoverHash) {
        String leftBase = baseTitle(effectiveTitle(left));
        String rightBase = baseTitle(effectiveTitle(right));
        int title = (int) Math.round(similarity(leftBase, rightBase) * 100D);
        Integer cover = coverSimilarity(leftCoverHash, rightCoverHash);
        int metadata = tagSimilarity(left, right);

        int score = (int) Math.round(title * 0.75D + (cover == null ? 0D : cover * 0.15D) + metadata * 0.10D);
        // 没有视觉指纹时，把权重归还给标题，避免历史画廊永远无法获得建议。
        if (cover == null) score = (int) Math.round(title * 0.90D + metadata * 0.10D);
        score = Math.max(0, Math.min(100, score));

        List<String> reasons = new ArrayList<>();
        reasons.add("系列标题 " + title + "%");
        if (cover != null) reasons.add("封面 " + cover + "%");
        else reasons.add("暂无封面指纹");
        if (metadata > 0) reasons.add("作者/原作等标签 " + metadata + "%");
        return new SeriesMatch(score, title, cover, metadata, String.join("；", reasons), leftBase, rightBase);
    }

    /** 去掉 EH 常见的活动/社团前缀、汉化标记和卷章节号，得到系列主体。 */
    public static String baseTitle(String raw) {
        if (raw == null) return "";
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        value = TRANSLATION_BLOCK.matcher(value).replaceAll(" ");
        value = LEADING_EVENT_OR_CREDIT.matcher(value).replaceFirst(" ");
        value = VOLUME_MARKER.matcher(value).replaceAll(" ");
        value = TRAILING_NUMBER.matcher(value).replaceFirst(" ");
        return NON_WORD.matcher(value).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    static double similarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) return 0D;
        if (left.equals(right)) return 1D;
        Set<String> a = ngrams(left.replace(" ", ""));
        Set<String> b = ngrams(right.replace(" ", ""));
        int intersection = 0;
        for (String gram : a) if (b.contains(gram)) intersection++;
        double dice = a.isEmpty() || b.isEmpty() ? 0D : 2D * intersection / (a.size() + b.size());
        double containment = (left.contains(right) || right.contains(left))
                ? Math.min(left.length(), right.length()) * 1D / Math.max(left.length(), right.length()) : 0D;
        return Math.max(dice, containment);
    }

    private static Set<String> ngrams(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() <= 2) {
            if (!value.isBlank()) result.add(value);
            return result;
        }
        for (int i = 0; i <= value.length() - 3; i++) result.add(value.substring(i, i + 3));
        return result;
    }

    private static Integer coverSimilarity(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return null;
        try {
            int distance = PerceptualHash.distance(left, right);
            return Math.max(0, (int) Math.round((1D - distance / 64D) * 100D));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int tagSimilarity(EhGalleriesEntity left, EhGalleriesEntity right) {
        Set<String> a = supportingTags(left);
        Set<String> b = supportingTags(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int intersection = 0;
        for (String tag : a) if (b.contains(tag)) intersection++;
        return (int) Math.round(intersection * 100D / (a.size() + b.size() - intersection));
    }

    private static Set<String> supportingTags(EhGalleriesEntity gallery) {
        if (gallery == null || gallery.getTags() == null) return Set.of();
        Set<String> result = new HashSet<>();
        for (String tag : gallery.getTags()) {
            if (tag == null) continue;
            String normalized = Normalizer.normalize(tag, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
            int separator = normalized.indexOf(':');
            if (separator > 0 && SUPPORT_NAMESPACES.contains(normalized.substring(0, separator))) result.add(normalized);
        }
        return result;
    }

    private static String effectiveTitle(EhGalleriesEntity gallery) {
        if (gallery == null) return "";
        return gallery.getOriginalTitle() == null || gallery.getOriginalTitle().isBlank()
                ? gallery.getTitle() : gallery.getOriginalTitle();
    }

    public record SeriesMatch(int score, int titleSimilarity, Integer coverSimilarity,
                              int metadataSimilarity, String reason,
                              String leftBaseTitle, String rightBaseTitle) {
    }
}
