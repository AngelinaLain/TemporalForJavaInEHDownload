package com.checker.common;

import com.checker.entity.EhGalleriesEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于 EH 元数据对同一作品的不同翻译版本进行保守归组。
 * <p>
 * 不使用中文标题作为唯一依据：只有原始标题，或“标题 + 至少两个核心标签”
 * 足以构成稳定身份时，才生成去重指纹。
 */
public final class GalleryDeduplication {

    private static final Set<String> IDENTITY_NAMESPACES = Set.of("parody", "character", "artist", "group");
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
            gallery.setDedupeConfidence(identity.confidence());
        }, () -> {
            gallery.setDedupeKey(null);
            gallery.setDedupeConfidence(null);
        });
    }

    public static boolean isIdentifiable(EhGalleriesEntity gallery) {
        return gallery != null && gallery.getDedupeKey() != null && !gallery.getDedupeKey().isBlank();
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

        int confidence = hasOriginalTitle ? (identityTags.isEmpty() ? 90 : 100) : 85;
        String payload = "title=" + title + "|tags=" + String.join(",", identityTags);
        return Optional.of(new Identity(sha256(payload), confidence));
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

    public record Identity(String key, int confidence) {
    }
}
