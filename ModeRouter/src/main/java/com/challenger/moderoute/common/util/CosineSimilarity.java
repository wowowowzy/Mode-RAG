package com.challenger.moderoute.common.util;

/**
 * 余弦相似度。内存检索用，必须与 sqlite-vec 的语义一致：
 * 那边返回 distance = 1 - cosine，这边直接返回 cosine，所以 score 可直接对齐。
 *
 * 纯算法，不依赖任何 IO，便于单测比对两条检索路径。
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    /** 维度不一致、空向量、零向量一律返回 0，不抛异常（检索场景下容忍脏数据）。 */
    public static double of(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0d || normB == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
