package com.checker.common;

/**
 * 保存一次去重判断使用的参数，统一约束前端设置、自动归组和视觉推荐的取值范围。
 * 参数对象不可变，同一轮比较可以使用同一份设置，避免比较过程中规则发生变化。
 */
public record DedupeSettings(int reviewScore, int autoMatchScore, int titleSimilarityPercent,
                             int visualPageDistance, int visualMinMatchedPages,
                             int visualMinCoverage, int visualQualityDelta) {
    public DedupeSettings {
        requireRange(reviewScore, 40, 94, "待审核分数");
        requireRange(autoMatchScore, 60, 100, "自动合并分数");
        if (reviewScore >= autoMatchScore) {
            throw new IllegalArgumentException("待审核分数必须小于自动合并分数");
        }
        requireRange(titleSimilarityPercent, 70, 100, "标题相似度");
        requireRange(visualPageDistance, 0, 24, "页面指纹差异上限");
        requireRange(visualMinMatchedPages, 2, 16, "最少匹配页数");
        requireRange(visualMinCoverage, 30, 100, "最低采样覆盖率");
        requireRange(visualQualityDelta, 1, 30, "画质推荐差距");
    }

    /** 返回默认去重规则，新安装或尚未保存设置时使用。 */
    public static DedupeSettings defaults() {
        return new DedupeSettings(65, 85, 88, 18, 3, 70, 3);
    }

    private static void requireRange(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }
}
