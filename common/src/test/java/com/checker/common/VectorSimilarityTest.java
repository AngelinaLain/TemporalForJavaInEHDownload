package com.checker.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorSimilarityTest {
    @Test
    void calculatesCosineSimilarity() {
        assertEquals(1D, VectorSimilarity.cosine(new float[]{1, 2}, new float[]{2, 4}), 0.000001);
        assertEquals(0D, VectorSimilarity.cosine(new float[]{1, 0}, new float[]{0, 1}), 0.000001);
    }

    @Test
    void rejectsInvalidVectors() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorSimilarity.cosine(new float[]{1}, new float[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> VectorSimilarity.cosine(new float[]{0}, new float[]{0}));
    }
}
