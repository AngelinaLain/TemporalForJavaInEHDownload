package com.checker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingCodecTest {
    @Test
    void roundTripsFloats() {
        float[] values = {0.1f, -0.25f, 3.5f};
        assertArrayEquals(values, EmbeddingCodec.decode(EmbeddingCodec.encode(values), values.length));
    }

    @Test
    void rejectsDimensionMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingCodec.decode(new byte[4], 2));
    }
}
