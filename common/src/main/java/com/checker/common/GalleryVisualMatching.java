package com.checker.common;

import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryVisualMatch;

import java.util.Comparator;
import java.util.List;

/** Monotonic page-sequence alignment tolerant of inserted ads and missing pages. */
public final class GalleryVisualMatching {
    private static final int PAGE_DISTANCE_THRESHOLD = 18;

    private GalleryVisualMatching() {
    }

    public static GalleryVisualMatch match(Long leftGid, List<GalleryPageFingerprint> leftInput,
                                           Long rightGid, List<GalleryPageFingerprint> rightInput) {
        List<GalleryPageFingerprint> left = ordered(leftInput);
        List<GalleryPageFingerprint> right = ordered(rightInput);
        if (left.isEmpty() || right.isEmpty()) {
            return empty(leftGid, rightGid, left.size(), right.size());
        }

        int[][] dp = new int[left.size() + 1][right.size() + 1];
        int[][] distance = new int[left.size()][right.size()];
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                distance[i][j] = pageDistance(left.get(i), right.get(j));
                int diagonal = distance[i][j] <= PAGE_DISTANCE_THRESHOLD ? dp[i][j] + 1 : Integer.MIN_VALUE / 4;
                dp[i + 1][j + 1] = Math.max(diagonal, Math.max(dp[i][j + 1], dp[i + 1][j]));
            }
        }

        int i = left.size();
        int j = right.size();
        int matched = 0;
        int totalDistance = 0;
        long leftPixels = 0;
        long rightPixels = 0;
        int leftQuality = 0;
        int rightQuality = 0;
        while (i > 0 && j > 0) {
            if (distance[i - 1][j - 1] <= PAGE_DISTANCE_THRESHOLD && dp[i][j] == dp[i - 1][j - 1] + 1) {
                GalleryPageFingerprint lp = left.get(i - 1);
                GalleryPageFingerprint rp = right.get(j - 1);
                matched++;
                totalDistance += distance[i - 1][j - 1];
                leftPixels += pixels(lp);
                rightPixels += pixels(rp);
                leftQuality += value(lp.getQuality());
                rightQuality += value(rp.getQuality());
                i--;
                j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }

        int coverage = (int) Math.round(matched * 100D / Math.min(left.size(), right.size()));
        int pageSimilarity = matched == 0 ? 0 : (int) Math.round((1D - totalDistance / (64D * matched)) * 100D);
        int similarity = matched == 0 ? 0 : (int) Math.round(coverage * 0.70 + pageSimilarity * 0.30);
        double leftScore = qualityScore(leftPixels, leftQuality, matched);
        double rightScore = qualityScore(rightPixels, rightQuality, matched);
        int qualityDelta = matched == 0 ? 0 : (int) Math.round(Math.min(100, Math.abs(leftScore - rightScore)));
        Long recommended = qualityDelta < 3 ? null : leftScore > rightScore ? leftGid : rightGid;
        String reason = matched == 0
                ? "采样页中未找到可靠的单调匹配"
                : String.format("匹配 %d/%d 个较少侧采样页；覆盖率 %d%%；匹配页平均相似度 %d%%；质量差 %d%%",
                matched, Math.min(left.size(), right.size()), coverage, pageSimilarity, qualityDelta);

        return GalleryVisualMatch.builder()
                .leftGid(leftGid).rightGid(rightGid)
                .similarity(similarity).matchedPages(matched)
                .leftSamples(left.size()).rightSamples(right.size())
                .sampleCoverage(coverage).orderConsistency(matched > 1 ? 100 : matched * 100)
                .recommendedGid(recommended).qualityDelta(qualityDelta)
                .reason(reason).algorithmVersion(PerceptualHash.ALGORITHM_VERSION)
                .build();
    }

    private static int pageDistance(GalleryPageFingerprint left, GalleryPageFingerprint right) {
        return Math.min(PerceptualHash.distance(left.getPerceptualHash(), right.getPerceptualHash()),
                PerceptualHash.distance(left.getCenterHash(), right.getCenterHash()));
    }

    private static List<GalleryPageFingerprint> ordered(List<GalleryPageFingerprint> input) {
        if (input == null) return List.of();
        return input.stream().filter(item -> item != null && item.getPageIndex() != null)
                .sorted(Comparator.comparingInt(GalleryPageFingerprint::getPageIndex)).toList();
    }

    private static long pixels(GalleryPageFingerprint item) {
        return Math.max(0, value(item.getWidth())) * (long) Math.max(0, value(item.getHeight()));
    }

    private static double qualityScore(long pixels, int quality, int count) {
        if (count == 0) return 0;
        double averagePixels = Math.max(1, pixels / (double) count);
        double resolution = Math.min(100, Math.log10(averagePixels) / 7D * 100D);
        return resolution * 0.7 + quality / (double) count * 0.3;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static GalleryVisualMatch empty(Long leftGid, Long rightGid, int leftSamples, int rightSamples) {
        return GalleryVisualMatch.builder()
                .leftGid(leftGid).rightGid(rightGid).similarity(0).matchedPages(0)
                .leftSamples(leftSamples).rightSamples(rightSamples).sampleCoverage(0).orderConsistency(0)
                .qualityDelta(0).reason("一侧尚无可用视觉指纹")
                .algorithmVersion(PerceptualHash.ALGORITHM_VERSION).build();
    }
}
