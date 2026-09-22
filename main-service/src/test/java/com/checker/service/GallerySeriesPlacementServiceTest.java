package com.checker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GallerySeriesPlacementServiceTest {
    @Test
    void keepsValidUnicodeCollectionNameAsSeriesDirectory() {
        assertEquals("异花4-圣神官", GallerySeriesPlacementService.safeDirectory(" 异花4-圣神官 "));
    }

    @Test
    void rejectsPathSeparatorsAndWindowsReservedCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> GallerySeriesPlacementService.safeDirectory("合集/子目录"));
        assertThrows(IllegalArgumentException.class,
                () -> GallerySeriesPlacementService.safeDirectory("合集?"));
    }
}
