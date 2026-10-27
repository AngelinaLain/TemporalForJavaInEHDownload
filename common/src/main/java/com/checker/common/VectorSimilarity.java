package com.checker.common;

/**
 * 为视觉复核计算两组图片特征向量的相似程度，并排除无效向量。
 */
public final class VectorSimilarity {
    private VectorSimilarity() {
    }

    /**
     * 校验向量维度和数值后计算余弦相似度，零向量不能比较，结果限制在合法范围以消除浮点误差。
     */
    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("向量不能为空且维度必须一致");
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) {
                throw new IllegalArgumentException("向量包含非有限数值");
            }
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            throw new IllegalArgumentException("零向量不能计算余弦相似度");
        }
        double similarity = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        return Math.max(-1D, Math.min(1D, similarity));
    }
}
