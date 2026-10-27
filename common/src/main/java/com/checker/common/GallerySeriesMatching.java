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
 * 判断不同画廊是否属于同一个系列，主要比较去掉卷号后的标题，再参考作者、原作和封面。
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

    /**
     * 汇总系列标题、封面和相关标签的分数，并给出推荐理由；缺少封面时把主要权重交回标题。
     */
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

    /**
     * 去掉活动与署名前缀、汉化标记和卷章节编号，统一文字形式，提取用于系列比较的标题主体。
     */
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

    /**
     * 比较两侧原始标题与显示标题的各个候选组合，选择最接近的一组。
     */
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

    /**
     * 从原始标题和显示标题中提取完整主体及系列主干，兼容主标题与副标题写法。
     */
    private static List<String> titleCandidates(EhGalleriesEntity gallery) {
        if (gallery == null) return List.of();
        Set<String> result = new HashSet<>();
        addTitleCandidates(result, gallery.getOriginalTitle());
        addTitleCandidates(result, gallery.getTitle());
        return result.stream().toList();
    }

    /**
     * 清理一个标题，并把有效的完整主体和分隔符前的主干加入候选集合。
     */
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

    /**
     * 排除过短、没有识别价值的系列主干，按中文与西文设置不同的最小长度。
     */
    private static boolean isMeaningfulStem(String value) {
        if (value == null || value.isBlank()) return false;
        int codePoints = value.codePointCount(0, value.length());
        boolean containsCjk = value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA);
        return containsCjk ? codePoints >= 2 : codePoints >= 4;
    }

    /**
     * 综合标题短片段重合度与包含关系计算相似度，兼容同一系列不同副标题。
     */
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

    /**
     * 把标题拆成相邻文字片段，供标题重合度比较使用。
     */
    private static Set<String> ngrams(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() <= 2) {
            if (!value.isBlank()) result.add(value);
            return result;
        }
        for (int i = 0; i <= value.length() - 3; i++) result.add(value.substring(i, i + 3));
        return result;
    }

    /**
     * 把封面哈希距离换成相似度；任一封面指纹缺失时返回无结果。
     */
    private static Integer coverSimilarity(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return null;
        try {
            int distance = PerceptualHash.distance(left, right);
            return Math.max(0, (int) Math.round((1D - distance / 64D) * 100D));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 比较作者、社团、原作和角色等辅助标签，补充系列标题判断。
     */
    private static int tagSimilarity(EhGalleriesEntity left, EhGalleriesEntity right) {
        Set<String> a = supportingTags(left);
        Set<String> b = supportingTags(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int intersection = 0;
        for (String tag : a) if (b.contains(tag)) intersection++;
        return (int) Math.round(intersection * 100D / (a.size() + b.size() - intersection));
    }

    /**
     * 提取可帮助识别系列的标签，并补充标题中的作者署名。
     */
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

    /**
     * 从标题开头的署名块提取辅助身份，排除展会编号等不代表作者的前缀。
     */
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
