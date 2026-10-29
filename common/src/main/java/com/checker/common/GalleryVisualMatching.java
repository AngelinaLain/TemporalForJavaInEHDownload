package com.checker.common;

import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryVisualMatch;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按页面先后顺序比较两本画廊的抽样指纹，允许中间多出广告页或缺页，输出相似度与推荐版本。
 */
public final class GalleryVisualMatching {
    private GalleryVisualMatching() {
    }

    /**
     * 先整理两侧有效页面，再用动态规划寻找保持阅读顺序的最多相似页。
     * 回溯匹配后综合页面覆盖率和哈希距离计算分数；只有画质差距足够明显时才推荐某个版本。
     */
    public static GalleryVisualMatch match(Long leftGid, List<GalleryPageFingerprint> leftInput,
                                           Long rightGid, List<GalleryPageFingerprint> rightInput) {
        return match(leftGid, leftInput, rightGid, rightInput, DedupeSettings.defaults());
    }

    /** 按本轮设置比较页面；样本数量、覆盖率和画质差距都足够时才推荐版本。 */
    public static GalleryVisualMatch match(Long leftGid, List<GalleryPageFingerprint> leftInput,
                                           Long rightGid, List<GalleryPageFingerprint> rightInput,
                                           DedupeSettings settings) {
        if (settings == null) throw new IllegalArgumentException("去重参数不能为空");
        List<GalleryPageFingerprint> left = ordered(leftInput);
        List<GalleryPageFingerprint> right = ordered(rightInput);
        if (left.isEmpty() || right.isEmpty()) {
            return empty(leftGid, rightGid, left.size(), right.size());
        }

        int[][] dp = new int[left.size() + 1][right.size() + 1];
        int[][] costs = new int[left.size() + 1][right.size() + 1];
        byte[][] steps = new byte[left.size() + 1][right.size() + 1];
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                int bestCount = dp[i][j + 1];
                int bestCost = costs[i][j + 1];
                byte step = 1;
                if (better(dp[i + 1][j], costs[i + 1][j], bestCount, bestCost)) {
                    bestCount = dp[i + 1][j];
                    bestCost = costs[i + 1][j];
                    step = 2;
                }
                int distance = pageDistance(left.get(i), right.get(j));
                int diagonalCount = dp[i][j] + 1;
                int diagonalCost = costs[i][j] + distance;
                // 先尽量匹配更多页面；有多个同样长的对齐结果时，选整体哈希差异更小的一组。
                if (distance <= settings.visualPageDistance()
                        && (better(diagonalCount, diagonalCost, bestCount, bestCost)
                        || diagonalCount == bestCount && diagonalCost == bestCost)) {
                    bestCount = diagonalCount;
                    bestCost = diagonalCost;
                    step = 3;
                }
                dp[i + 1][j + 1] = bestCount;
                costs[i + 1][j + 1] = bestCost;
                steps[i + 1][j + 1] = step;
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
            if (steps[i][j] == 3) {
                GalleryPageFingerprint lp = left.get(i - 1);
                GalleryPageFingerprint rp = right.get(j - 1);
                matched++;
                totalDistance += pageDistance(lp, rp);
                leftPixels += pixels(lp);
                rightPixels += pixels(rp);
                leftQuality += value(lp.getQuality());
                rightQuality += value(rp.getQuality());
                i--;
                j--;
            } else if (steps[i][j] == 1) {
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
        boolean sufficientEvidence = matched >= settings.visualMinMatchedPages()
                && coverage >= settings.visualMinCoverage();
        Long recommended = !sufficientEvidence || qualityDelta < settings.visualQualityDelta()
                ? null : leftScore > rightScore ? leftGid : rightGid;
        String reason = matched == 0
                ? "采样页中未找到可靠的单调匹配"
                : String.format("匹配 %d/%d 个较少侧采样页；覆盖率 %d%%；匹配页平均相似度 %d%%；质量差 %d%%",
                matched, Math.min(left.size(), right.size()), coverage, pageSimilarity, qualityDelta);
        if (matched > 0 && !sufficientEvidence) {
            reason += String.format("；证据不足：推荐至少需要 %d 个匹配页且覆盖率达到 %d%%，暂不推荐版本",
                    settings.visualMinMatchedPages(), settings.visualMinCoverage());
        } else if (matched > 0 && recommended == null) {
            reason += "；画质差距未达到 " + settings.visualQualityDelta() + "%，暂不推荐版本";
        }

        return GalleryVisualMatch.builder()
                .leftGid(leftGid).rightGid(rightGid)
                .similarity(similarity).matchedPages(matched)
                .leftSamples(left.size()).rightSamples(right.size())
                .sampleCoverage(coverage).orderConsistency(matched > 1 ? 100 : matched * 100)
                .recommendedGid(recommended).qualityDelta(qualityDelta)
                .reason(reason).algorithmVersion(PerceptualHash.ALGORITHM_VERSION)
                .build();
    }

    /**
     * 比较整页和中心区域的感知哈希，取更接近的结果，减少边框或页面边缘变化的影响。
     */
    private static int pageDistance(GalleryPageFingerprint left, GalleryPageFingerprint right) {
        return Math.min(PerceptualHash.distance(left.getPerceptualHash(), right.getPerceptualHash()),
                PerceptualHash.distance(left.getCenterHash(), right.getCenterHash()));
    }

    /**
     * 排除无效页码与完全不可用的哈希，同一页只计算一次，防止重复记录抬高匹配证据。
     */
    private static List<GalleryPageFingerprint> ordered(List<GalleryPageFingerprint> input) {
        if (input == null) return List.of();
        Set<Integer> seenPages = new HashSet<>();
        return input.stream().filter(item -> item != null && item.getPageIndex() != null && item.getPageIndex() >= 0)
                .filter(item -> validHash(item.getPerceptualHash()) || validHash(item.getCenterHash()))
                .sorted(Comparator.comparingInt(GalleryPageFingerprint::getPageIndex))
                .filter(item -> seenPages.add(item.getPageIndex())).toList();
    }

    private static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{16}");
    }

    private static boolean better(int count, int cost, int otherCount, int otherCost) {
        return count > otherCount || count == otherCount && cost < otherCost;
    }

    private static long pixels(GalleryPageFingerprint item) {
        return Math.max(0, value(item.getWidth())) * (long) Math.max(0, value(item.getHeight()));
    }

    /**
     * 结合匹配页的像素数量和画质指标，估计该版本的阅读质量。
     */
    private static double qualityScore(long pixels, int quality, int count) {
        if (count == 0) return 0;
        double averagePixels = Math.max(1, pixels / (double) count);
        double resolution = Math.min(100, Math.log10(averagePixels) / 7D * 100D);
        return resolution * 0.7 + quality / (double) count * 0.3;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 在缺少可比较样本时返回明确的空匹配结果，保留双方样本数量。
     */
    private static GalleryVisualMatch empty(Long leftGid, Long rightGid, int leftSamples, int rightSamples) {
        return GalleryVisualMatch.builder()
                .leftGid(leftGid).rightGid(rightGid).similarity(0).matchedPages(0)
                .leftSamples(leftSamples).rightSamples(rightSamples).sampleCoverage(0).orderConsistency(0)
                .qualityDelta(0).reason("一侧尚无可用视觉指纹")
                .algorithmVersion(PerceptualHash.ALGORITHM_VERSION).build();
    }
}
