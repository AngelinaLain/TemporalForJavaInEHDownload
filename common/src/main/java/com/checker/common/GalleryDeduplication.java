package com.checker.common;

import com.checker.entity.EhGalleriesEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BiPredicate;

/**
 * 基于 EH 元数据对同一作品的不同翻译版本进行保守归组。
 * <p>
 * 不使用中文标题作为唯一依据：只有原始标题，或“标题 + 至少两个核心标签”
 * 足以构成稳定身份时，才生成去重指纹。
 */
public final class GalleryDeduplication {

    public static final int ALGORITHM_VERSION = 2;
    public static final int REVIEW_MATCH_THRESHOLD = 65;
    public static final int AUTO_MATCH_THRESHOLD = 85;
    private static final Set<String> IDENTITY_NAMESPACES = Set.of("parody", "character", "artist", "group");
    private static final Set<String> CREATOR_NAMESPACES = Set.of("artist", "group");
    private static final Pattern BRACKETED_SEGMENT = Pattern.compile("[\\[【(（][^\\]】)）]{0,80}[\\]】)）]");
    private static final Pattern TRANSLATION_MARKER = Pattern.compile(
            "(?i)chinese|中文|汉化|漢化|翻译|翻譯|translated|translation|简体|繁体|簡體|繁體");
    private static final Pattern NON_IDENTITY_CHARACTER = Pattern.compile(
            "[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsLatin}\\p{IsDigit}]+");

    private GalleryDeduplication() {
    }

    public static void populateIdentity(EhGalleriesEntity gallery) {
        calculateIdentity(gallery).ifPresentOrElse(identity -> {
            gallery.setDedupeKey(identity.key());
            gallery.setCandidateKey(identity.candidateKey());
            gallery.setDedupeConfidence(identity.confidence());
            gallery.setDedupeAlgorithmVersion(identity.algorithmVersion());
        }, () -> {
            gallery.setDedupeKey(null);
            gallery.setCandidateKey(null);
            gallery.setDedupeConfidence(null);
            gallery.setDedupeAlgorithmVersion(ALGORITHM_VERSION);
        });
    }

    public static boolean isIdentifiable(EhGalleriesEntity gallery) {
        return gallery != null && gallery.getCandidateKey() != null && !gallery.getCandidateKey().isBlank();
    }

    public static Optional<Identity> calculateIdentity(EhGalleriesEntity gallery) {
        if (gallery == null) {
            return Optional.empty();
        }

        String originalTitle = normalizeTitle(gallery.getOriginalTitle());
        boolean hasOriginalTitle = !originalTitle.isBlank();
        String title = hasOriginalTitle ? originalTitle : normalizeTitle(gallery.getTitle());
        if (title.length() < 2) {
            return Optional.empty();
        }

        List<String> identityTags = gallery.getTags() == null ? List.of() : gallery.getTags().stream()
                .filter(tag -> tag != null && tag.contains(":"))
                .map(GalleryDeduplication::normalizeTag)
                .filter(tag -> !tag.isBlank())
                .filter(tag -> IDENTITY_NAMESPACES.contains(tag.substring(0, tag.indexOf(':'))))
                .distinct()
                .sorted()
                .toList();

        if (!hasOriginalTitle && identityTags.size() < 2) {
            return Optional.empty();
        }

        List<String> creatorTags = identityTags.stream()
                .filter(tag -> CREATOR_NAMESPACES.contains(namespace(tag)))
                .toList();
        int confidence = hasOriginalTitle ? (creatorTags.isEmpty() ? 90 : 100) : 85;

        // strict key 用标题 + 作者/社团，避免 character/parody 标签抓取数量变化导致完全无法召回。
        String strictPayload = "v=" + ALGORITHM_VERSION + "|title=" + title
                + "|creators=" + String.join(",", creatorTags);
        // candidate key 是较宽松的阻塞键；真正是否同作品由 match() 的多信号评分决定。
        String candidateStem = firstCodePoints(title, 24);
        String candidateKey = sha256("v=" + ALGORITHM_VERSION + "|candidate=" + candidateStem);
        return Optional.of(new Identity(
                sha256(strictPayload), candidateKey, confidence, ALGORITHM_VERSION));
    }

    /**
     * 对候选画廊做可解释的多信号评分。只有候选键相同的记录才应调用此方法。
     */
    public static MatchResult match(EhGalleriesEntity left, EhGalleriesEntity right) {
        if (left == null || right == null) {
            return new MatchResult(0, "缺少画廊数据");
        }
        if (left.getGid() != null && left.getGid().equals(right.getGid())) {
            return new MatchResult(100, "GID 完全相同");
        }
        if (notBlank(left.getDedupeKey()) && left.getDedupeKey().equals(right.getDedupeKey())) {
            return new MatchResult(100, "严格作品指纹相同");
        }

        int score = 0;
        List<String> reasons = new ArrayList<>();
        String leftOriginal = normalizeTitle(left.getOriginalTitle());
        String rightOriginal = normalizeTitle(right.getOriginalTitle());
        String leftTitle = effectiveTitle(left);
        String rightTitle = effectiveTitle(right);

        if (!leftOriginal.isBlank() && leftOriginal.equals(rightOriginal)) {
            score += 70;
            reasons.add("原始标题相同 +70");
        } else if (!leftTitle.isBlank() && leftTitle.equals(rightTitle)) {
            score += 55;
            reasons.add("标准化标题相同 +55");
        } else {
            double similarity = titleSimilarity(leftTitle, rightTitle);
            if (similarity >= 0.88D) {
                int points = (int) Math.round(similarity * 50D);
                score += points;
                reasons.add("标题相似 " + Math.round(similarity * 100D) + "% +" + points);
            }
        }

        Set<String> leftCreators = tagsInNamespaces(left, CREATOR_NAMESPACES);
        Set<String> rightCreators = tagsInNamespaces(right, CREATOR_NAMESPACES);
        if (!leftCreators.isEmpty() && !rightCreators.isEmpty()) {
            if (intersectionSize(leftCreators, rightCreators) > 0) {
                score += 20;
                reasons.add("作者/社团重合 +20");
            } else {
                score -= 35;
                reasons.add("作者/社团冲突 -35");
            }
        }

        score += namespaceOverlapScore(left, right, "parody", 10, -10, reasons, "原作");

        Set<String> leftCharacters = tagsInNamespaces(left, Set.of("character"));
        Set<String> rightCharacters = tagsInNamespaces(right, Set.of("character"));
        if (!leftCharacters.isEmpty() && !rightCharacters.isEmpty()) {
            double jaccard = jaccard(leftCharacters, rightCharacters);
            int points = (int) Math.round(jaccard * 10D);
            score += points;
            if (points > 0) reasons.add("角色重合 +" + points);
        }

        if (left.getPageCount() != null && left.getPageCount() > 0
                && right.getPageCount() != null && right.getPageCount() > 0) {
            double ratio = Math.min(left.getPageCount(), right.getPageCount()) * 1D
                    / Math.max(left.getPageCount(), right.getPageCount());
            if (ratio >= 0.95D) {
                score += 5;
                reasons.add("页数接近 +5");
            } else if (ratio >= 0.80D) {
                score += 2;
                reasons.add("页数基本接近 +2");
            }
        }

        int bounded = Math.max(0, Math.min(100, score));
        return new MatchResult(bounded, reasons.isEmpty() ? "没有足够的共同信号" : String.join("；", reasons));
    }

    public static boolean isAutomaticMatch(EhGalleriesEntity left, EhGalleriesEntity right) {
        return match(left, right).score() >= AUTO_MATCH_THRESHOLD;
    }

    /**
     * 使用保守的完全连接规则聚类：新成员必须与组内每条记录都达到自动阈值。
     * 这样可避免 A≈B、B≈C 但 A≉C 时发生链式误合并。
     */
    public static List<List<EhGalleriesEntity>> clusterCandidates(List<EhGalleriesEntity> candidates) {
        return clusterCandidates(candidates, GalleryDeduplication::isAutomaticMatch);
    }

    /**
     * 使用外部判定器聚类，供持久化人工 MATCH/DIFFERENT 结论覆盖自动评分。
     */
    public static List<List<EhGalleriesEntity>> clusterCandidates(
            List<EhGalleriesEntity> candidates,
            BiPredicate<EhGalleriesEntity, EhGalleriesEntity> matcher) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (matcher == null) throw new IllegalArgumentException("候选匹配器不能为空");
        List<EhGalleriesEntity> ordered = candidates.stream()
                .filter(item -> item != null && item.getGid() != null)
                .sorted(Comparator.comparingLong(EhGalleriesEntity::getGid))
                .toList();

        List<List<EhGalleriesEntity>> groups = new ArrayList<>();
        for (EhGalleriesEntity candidate : ordered) {
            List<EhGalleriesEntity> matchingGroup = groups.stream()
                    .filter(group -> group.stream()
                            .allMatch(member -> matcher.test(member, candidate)))
                    .findFirst()
                    .orElse(null);
            if (matchingGroup == null) {
                matchingGroup = new ArrayList<>();
                groups.add(matchingGroup);
            }
            matchingGroup.add(candidate);
        }
        return groups;
    }

    /** 评分优先，其次选择页数更多、GID 更新的版本。 */
    public static EhGalleriesEntity choosePreferred(List<EhGalleriesEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("候选画廊不能为空");
        }
        Comparator<EhGalleriesEntity> preference = Comparator
                .comparingDouble((EhGalleriesEntity gallery) -> valueOrZero(gallery.getRating()))
                .thenComparingInt(gallery -> intOrZero(gallery.getPageCount()))
                .thenComparingLong(gallery -> gallery.getGid() == null ? Long.MIN_VALUE : gallery.getGid());
        return candidates.stream().max(preference).orElseThrow();
    }

    private static String normalizeTag(String tag) {
        String normalized = Normalizer.normalize(tag, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) {
            return "";
        }
        return normalized.substring(0, separator) + ":" + normalizeTitle(normalized.substring(separator + 1));
    }

    private static String effectiveTitle(EhGalleriesEntity gallery) {
        String original = normalizeTitle(gallery.getOriginalTitle());
        return original.isBlank() ? normalizeTitle(gallery.getTitle()) : original;
    }

    private static Set<String> tagsInNamespaces(EhGalleriesEntity gallery, Set<String> namespaces) {
        if (gallery == null || gallery.getTags() == null) return Set.of();
        Set<String> result = new HashSet<>();
        for (String raw : gallery.getTags()) {
            String normalized = normalizeTag(raw);
            if (!normalized.isBlank() && namespaces.contains(namespace(normalized))) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static int namespaceOverlapScore(EhGalleriesEntity left, EhGalleriesEntity right,
                                             String namespace, int overlapPoints, int conflictPoints,
                                             List<String> reasons, String label) {
        Set<String> leftTags = tagsInNamespaces(left, Set.of(namespace));
        Set<String> rightTags = tagsInNamespaces(right, Set.of(namespace));
        if (leftTags.isEmpty() || rightTags.isEmpty()) return 0;
        if (intersectionSize(leftTags, rightTags) > 0) {
            reasons.add(label + "重合 +" + overlapPoints);
            return overlapPoints;
        }
        reasons.add(label + "冲突 " + conflictPoints);
        return conflictPoints;
    }

    private static String namespace(String tag) {
        int separator = tag.indexOf(':');
        return separator > 0 ? tag.substring(0, separator) : "";
    }

    private static int intersectionSize(Set<String> left, Set<String> right) {
        int count = 0;
        for (String item : left) if (right.contains(item)) count++;
        return count;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        int intersection = intersectionSize(left, right);
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0D : intersection * 1D / union;
    }

    private static double titleSimilarity(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return 0D;
        if (left.equals(right)) return 1D;
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1D - previous[right.length()] * 1D / Math.max(left.length(), right.length());
    }

    private static String firstCodePoints(String value, int maxCodePoints) {
        int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), maxCodePoints));
        return value.substring(0, end);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = BRACKETED_SEGMENT.matcher(normalized).replaceAll(" ");
        normalized = TRANSLATION_MARKER.matcher(normalized).replaceAll(" ");
        return NON_IDENTITY_CHARACTER.matcher(normalized).replaceAll("");
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0D : value;
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    public record Identity(String key, String candidateKey, int confidence, int algorithmVersion) {
    }

    public record MatchResult(int score, String reason) {
    }
}
