package com.checker.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeSettingsTest {
    @Test
    void providesCompatibleDefaultsAndAcceptsRangeBoundaries() {
        assertEquals(new DedupeSettings(65, 85, 88, 18, 3, 70, 3), DedupeSettings.defaults());
        new DedupeSettings(40, 60, 70, 0, 2, 30, 1);
        new DedupeSettings(94, 100, 100, 24, 16, 100, 30);
    }

    @Test
    void rejectsEachOutOfRangeSettingAndInvalidThresholdOrder() {
        int[][] invalid = {
                {39, 85, 88, 18, 3, 70, 3}, {95, 100, 88, 18, 3, 70, 3},
                {40, 59, 88, 18, 3, 70, 3}, {65, 101, 88, 18, 3, 70, 3},
                {65, 85, 69, 18, 3, 70, 3}, {65, 85, 101, 18, 3, 70, 3},
                {65, 85, 88, -1, 3, 70, 3}, {65, 85, 88, 25, 3, 70, 3},
                {65, 85, 88, 18, 1, 70, 3}, {65, 85, 88, 18, 17, 70, 3},
                {65, 85, 88, 18, 3, 29, 3}, {65, 85, 88, 18, 3, 101, 3},
                {65, 85, 88, 18, 3, 70, 0}, {65, 85, 88, 18, 3, 70, 31},
                {85, 85, 88, 18, 3, 70, 3}, {90, 80, 88, 18, 3, 70, 3}
        };
        for (int[] values : invalid) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new DedupeSettings(values[0], values[1], values[2], values[3], values[4], values[5], values[6]));
            assertTrue(error.getMessage().matches(".*[\\p{IsHan}].*"));
        }
    }
}
