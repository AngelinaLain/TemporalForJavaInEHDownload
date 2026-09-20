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
            "^(?:\\s*(?:\\[[^\\]]{1,120}]|【[^】]{1,120}】|\\([^)]{1,120}\\)|（[^）]{1,120}）)\\s*)+");
    private static final Pattern TRANSLATION_BLOCK = Pattern.compile(
            "(?i)[\\[【(（][^\\]】)）]*(?:chinese|english|中文|中國翻譯|中国翻译|中国翻訳|漢化|汉化|翻译|翻譯|無修正|无修正|digital|カラー)[^\\]】)）]*[\\]】)）]");
    private static final Pattern VOLUME_MARKER = Pattern.compile(
            "(?iu)(?:\\b(?:vol(?:ume)?|ch(?:apter)?|part|episode|ep|book|season)\\.?\\s*[-_:]?\\s*(?:\\d+(?:\\.\\d+)?|[ivxlcdm]+)\\b|第\\s*[0-9０-９一二三四五六七八九十百]+\\s*[巻卷話话章节集部]|(?:その|其)\\s*[0-9０-９一二三四五六七八九十百]+)");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?iu)(?:[-_:# ]+(?:\\d+(?:\\.\\d+)?|[ivxlcdm]+))$");
    private static final Pattern ATTACHED_SERIES_NUMBER = Pattern.compile(
            "(?iu)([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsLatin}])\\s*[0-9０-９]+(?=\\s*[-_:：])");
    private static final Pattern TITLE_DELIMITER = Pattern.compile("\\s*[-_:：]\\s*");
    private static final Pattern LEADING_CREDIT = Pattern.compile("^\\s*\\[([^\\]]{2,120})]\\s*");
    private static final Pattern EVENT_CREDIT = Pattern.compile("(?iu)^(?:c|comiket|コミケ)\\s*\\d+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsLatin}\\p{IsDigit}]+");
    private static final Set<String> SUPPORT_NAMESPACES = Set.of("artist", "group", "parody", "character");

    private GallerySeriesMatching() {
    }

    public static SeriesMatch score(EhGalleriesEntity left, String leftCoverHash,
                                    EhGalleriesEntity right, String rightCoverHash) {
        TitleMatch titleMatch = titleMatch(left, right);
        String leftBase = titleMatch.leftBase();
        String rightBase = titleMatch.rightBase();
        int title = titleMatch.similarity();
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
        value = ATTACHED_SERIES_NUMBER.matcher(value).replaceAll("$1");
        value = TRAILING_NUMBER.matcher(value).replaceFirst(" ");
        return NON_WORD.matcher(value).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private static TitleMatch titleMatch(EhGalleriesEntity left, EhGalleriesEntity right) {
        List<String> leftTitles = titleCandidates(left);
        List<String> rightTitles = titleCandidates(right);
        double best = 0D;
        String bestLeft = "";
        String bestRight = "";
        for (String leftTitle : leftTitles) {
            for (String rightTitle : rightTitles) {
                double candidate = similarity(leftTitle, rightTitle);
                if (candidate > best) {
                    best = candidate;
                    bestLeft = leftTitle;
                    bestRight = rightTitle;
                }
            }
        }
        return new TitleMatch((int) Math.round(best * 100D), bestLeft, bestRight);
    }

    /** 同时保留完整标题与 EH 常见“主标题 - 副标题”中的系列主干。 */
    private static List<String> titleCandidates(EhGalleriesEntity gallery) {
        if (gallery == null) return List.of();
        Set<String> result = new HashSet<>();
        addTitleCandidates(result, gallery.getOriginalTitle());
        addTitleCandidates(result, gallery.getTitle());
        return result.stream().toList();
    }

    private static void addTitleCandidates(Set<String> result, String raw) {
        if (raw == null || raw.isBlank()) return;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        normalized = TRANSLATION_BLOCK.matcher(normalized).replaceAll(" ");
        normalized = LEADING_EVENT_OR_CREDIT.matcher(normalized).replaceFirst(" ").trim();
        normalized = VOLUME_MARKER.matcher(normalized).replaceAll(" ");
        normalized = ATTACHED_SERIES_NUMBER.matcher(normalized).replaceAll("$1");
        String[] segments = TITLE_DELIMITER.split(normalized, 2);
        String full = baseTitle(raw);
        if (!full.isBlank()) result.add(full);
        if (segments.length > 1) {
            String stem = NON_WORD.matcher(segments[0]).replaceAll(" ").trim().replaceAll("\\s+", " ");
            if (isMeaningfulStem(stem)) result.add(stem);
        }
    }

    private static boolean isMeaningfulStem(String value) {
        if (value == null || value.isBlank()) return false;
        int codePoints = value.codePointCount(0, value.length());
        boolean containsCjk = value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA);
        return containsCjk ? codePoints >= 2 : codePoints >= 4;
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
        if (gallery == null) return Set.of();
        Set<String> result = new HashSet<>();
        if (gallery.getTags() != null) {
            for (String tag : gallery.getTags()) {
                if (tag == null) continue;
                String normalized = Normalizer.normalize(tag, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
                int separator = normalized.indexOf(':');
                if (separator > 0 && SUPPORT_NAMESPACES.contains(normalized.substring(0, separator))) result.add(normalized);
            }
        }
        addLeadingCredit(result, gallery.getOriginalTitle());
        addLeadingCredit(result, gallery.getTitle());
        return result;
    }

    private static void addLeadingCredit(Set<String> result, String raw) {
        if (raw == null) return;
        java.util.regex.Matcher matcher = LEADING_CREDIT.matcher(
                Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        if (!matcher.find()) return;
        String credit = NON_WORD.matcher(matcher.group(1)).replaceAll(" ").trim().replaceAll("\\s+", " ");
        if (!credit.isBlank() && !EVENT_CREDIT.matcher(credit).find()) result.add("credit:" + credit);
    }

    public record SeriesMatch(int score, int titleSimilarity, Integer coverSimilarity,
                              int metadataSimilarity, String reason,
                              String leftBaseTitle, String rightBaseTitle) {
    }

    private record TitleMatch(int similarity, String leftBase, String rightBase) {
    }
}
