package com.checker.common;

import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryVisualMatch;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryVisualMatchingTest {
    @Test
    void alignsPagesDespiteInsertedAdvertisements() {
        List<GalleryPageFingerprint> left = pages(1L, 0, 8, 1000, 1400);
        List<GalleryPageFingerprint> right = new ArrayList<>();
        right.add(page(2L, 0, "aaaaaaaaaaaaaaaa", 800, 1000));
        for (int i = 0; i < 8; i++) {
            GalleryPageFingerprint source = left.get(i);
            right.add(page(2L, i + 1, source.getPerceptualHash(), 1400, 2000));
        }
        right.add(page(2L, 9, "5555555555555555", 800, 1000));

        GalleryVisualMatch match = GalleryVisualMatching.match(1L, left, 2L, right);

        assertEquals(8, match.getMatchedPages());
        assertEquals(100, match.getSampleCoverage());
        assertTrue(match.getSimilarity() >= 95);
        assertEquals(2L, match.getRecommendedGid());
    }

    @Test
    void doesNotRecommendVersionBasedOnSingleMatchingPage() {
        GalleryVisualMatch match = GalleryVisualMatching.match(1L, pages(1L, 0, 1, 600, 900),
                2L, pages(2L, 0, 1, 2400, 3600));

        assertEquals(1, match.getMatchedPages());
        assertEquals(100, match.getSimilarity());
        assertNull(match.getRecommendedGid());
        assertTrue(match.getReason().contains("证据不足"));
    }

    @Test
    void appliesConfiguredMinimumMatchedPagesAndQualityGap() {
        List<GalleryPageFingerprint> left = pages(1L, 0, 2, 1000, 1400);
        List<GalleryPageFingerprint> right = pages(2L, 0, 2, 1400, 2000);

        assertNull(GalleryVisualMatching.match(1L, left, 2L, right).getRecommendedGid());
        assertEquals(2L, GalleryVisualMatching.match(1L, left, 2L, right,
                new DedupeSettings(65, 85, 88, 18, 2, 70, 3)).getRecommendedGid());
        GalleryVisualMatch highGap = GalleryVisualMatching.match(1L, left, 2L, right,
                new DedupeSettings(65, 85, 88, 18, 2, 70, 30));
        assertNull(highGap.getRecommendedGid());
        assertTrue(highGap.getReason().contains("画质差距未达到 30%"));
    }

    @Test
    void requiresConfiguredCoverageEvenWhenEnoughPagesMatch() {
        List<GalleryPageFingerprint> left = pages(1L, 0, 4, 1000, 1400);
        List<GalleryPageFingerprint> right = List.of(
                page(2L, 0, left.get(0).getPerceptualHash(), 2000, 2800),
                page(2L, 1, left.get(1).getPerceptualHash(), 2000, 2800),
                page(2L, 2, "ffffffffffffffff", 2000, 2800),
                page(2L, 3, "fffffffffffffffe", 2000, 2800));

        GalleryVisualMatch strict = GalleryVisualMatching.match(1L, left, 2L, right,
                new DedupeSettings(65, 85, 88, 18, 2, 70, 3));
        assertEquals(2, strict.getMatchedPages());
        assertEquals(50, strict.getSampleCoverage());
        assertNull(strict.getRecommendedGid());
        assertEquals(2L, GalleryVisualMatching.match(1L, left, 2L, right,
                new DedupeSettings(65, 85, 88, 18, 2, 30, 3)).getRecommendedGid());
    }

    @Test
    void usesConfiguredPageDistance() {
        List<GalleryPageFingerprint> left = pages(1L, 0, 3, 1000, 1400);
        List<GalleryPageFingerprint> right = new ArrayList<>();
        for (int index = 0; index < left.size(); index++) {
            String hash = String.format("%016x", Long.parseUnsignedLong(left.get(index).getPerceptualHash(), 16) ^ 3L);
            right.add(page(2L, index, hash, 1000, 1400));
        }

        assertEquals(0, GalleryVisualMatching.match(1L, left, 2L, right,
                new DedupeSettings(65, 85, 88, 0, 3, 70, 3)).getMatchedPages());
        assertEquals(3, GalleryVisualMatching.match(1L, left, 2L, right,
                new DedupeSettings(65, 85, 88, 2, 3, 70, 3)).getMatchedPages());
    }

    @Test
    void filtersInvalidHashesAndCountsEachValidPageOnce() {
        GalleryPageFingerprint centerOnly = page(1L, 2, null, 1000, 1400);
        centerOnly.setCenterHash("3333333333333333");
        List<GalleryPageFingerprint> left = Arrays.asList(null,
                page(1L, -1, "0000000000000000", 1000, 1400),
                page(1L, 0, "0000000000000000", 1000, 1400),
                page(1L, 0, "0000000000000000", 1000, 1400),
                page(1L, 1, "invalid", 1000, 1400), centerOnly);
        List<GalleryPageFingerprint> right = List.of(
                page(2L, 0, "0000000000000000", 1000, 1400),
                page(2L, 2, "3333333333333333", 1000, 1400));

        GalleryVisualMatch match = GalleryVisualMatching.match(1L, left, 2L, right);
        assertEquals(2, match.getLeftSamples());
        assertEquals(2, match.getMatchedPages());
        assertNull(match.getRecommendedGid());
        assertEquals(0, GalleryVisualMatching.match(1L,
                List.of(page(1L, 0, "invalid", 1000, 1400)), 2L, right).getLeftSamples());
    }

    @Test
    void choosesSmallerDistanceWhenSeveralAlignmentsMatchSameNumberOfPages() {
        List<GalleryPageFingerprint> left = List.of(page(1L, 0, "0000000000000000", 1000, 1400));
        List<GalleryPageFingerprint> right = List.of(
                page(2L, 0, "0000000000000000", 1000, 1400),
                page(2L, 1, "000000000000000f", 1000, 1400));

        GalleryVisualMatch match = GalleryVisualMatching.match(1L, left, 2L, right);

        assertEquals(1, match.getMatchedPages());
        assertEquals(100, match.getSimilarity());
        assertTrue(match.getReason().contains("平均相似度 100%"));
    }

    @Test
    void hashesSameDrawingAtDifferentResolutionClosely() throws Exception {
        GalleryPageFingerprint small = hashDrawing(1L, 320, 480);
        GalleryPageFingerprint large = hashDrawing(2L, 960, 1440);

        assertNotNull(small);
        assertNotNull(large);
        assertEquals(2, small.getAlgorithmVersion());
        assertEquals(16, small.getPerceptualHash().length());
        assertEquals(16, small.getCenterHash().length());
        assertTrue(small.getPerceptualHash().matches("[0-9a-f]{16}"));
        assertTrue(PerceptualHash.distance(small.getPerceptualHash(), large.getPerceptualHash()) <= 8);
    }

    @Test
    void hashesConcurrentlyWithTheSharedJImageHashInstance() throws Exception {
        byte[] image = drawingBytes(640, 960);
        List<CompletableFuture<GalleryPageFingerprint>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int pageIndex = i;
            tasks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return PerceptualHash.fingerprint(new ByteArrayInputStream(image), 1L,
                            pageIndex, "page.png", "TEST");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
        }

        List<GalleryPageFingerprint> fingerprints = tasks.stream().map(CompletableFuture::join).toList();
        assertEquals(1, fingerprints.stream().map(GalleryPageFingerprint::getPerceptualHash).distinct().count());
        assertEquals(1, fingerprints.stream().map(GalleryPageFingerprint::getCenterHash).distinct().count());
    }

    @Test
    void convertsCmykRasterWithoutRelyingOnEmbeddedColorProfile() {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 1, 1, 4, null);
        raster.setPixel(0, 0, new int[]{0, 255, 255, 0});

        BufferedImage converted = PerceptualHash.rgbFromRaster(raster);

        assertEquals(0xff0000, converted.getRGB(0, 0) & 0xffffff);
    }

    @Test
    void ignoresIccProfileWhoseComponentsDoNotMatchJpegRaster() throws Exception {
        BufferedImage grayscale = new BufferedImage(80, 120, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = grayscale.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 80, 120);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(10, 20, 60, 70);
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(grayscale, "jpeg", encoded);

        byte[] malformed = withIccProfile(encoded.toByteArray(),
                ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
        byte[] sanitized = PerceptualHash.removeJpegIccProfile(malformed);

        GalleryPageFingerprint fingerprint = PerceptualHash.fingerprint(
                new ByteArrayInputStream(malformed), 3L, 0, "broken-icc.jpg", "TEST");

        assertNotNull(sanitized);
        assertTrue(sanitized.length < malformed.length);
        assertNotNull(fingerprint);
        assertEquals(80, fingerprint.getWidth());
        assertEquals(120, fingerprint.getHeight());
    }

    private GalleryPageFingerprint hashDrawing(Long gid, int width, int height) throws Exception {
        return PerceptualHash.fingerprint(new ByteArrayInputStream(drawingBytes(width, height)),
                gid, 0, "page.png", "TEST");
    }

    private byte[] drawingBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(width / 8, height / 8, width * 3 / 4, height / 8);
        graphics.drawOval(width / 4, height / 3, width / 2, height / 3);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private byte[] withIccProfile(byte[] jpeg, byte[] profile) {
        byte[] signature = "ICC_PROFILE\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int payloadLength = signature.length + 2 + profile.length;
        int segmentLength = payloadLength + 2;
        ByteArrayOutputStream output = new ByteArrayOutputStream(jpeg.length + segmentLength + 2);
        output.write(jpeg, 0, 2);
        output.write(0xff);
        output.write(0xe2);
        output.write(segmentLength >>> 8);
        output.write(segmentLength & 0xff);
        output.writeBytes(signature);
        output.write(1);
        output.write(1);
        output.writeBytes(profile);
        output.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
    }

    private List<GalleryPageFingerprint> pages(Long gid, int start, int count, int width, int height) {
        List<GalleryPageFingerprint> result = new ArrayList<>();
        String[] hashes = {
                "0000000000000000", "0f0f0f0f0f0f0f0f", "3333333333333333", "00ff00ff00ff00ff",
                "0000ffff0000ffff", "3c3c3c3c3c3c3c3c", "0ff00ff00ff00ff0", "3333cccc3333cccc"
        };
        for (int i = 0; i < count; i++) result.add(page(gid, start + i, hashes[i], width, height));
        return result;
    }

    private GalleryPageFingerprint page(Long gid, int index, String hash, int width, int height) {
        return GalleryPageFingerprint.builder()
                .gid(gid).pageIndex(index).pageName(index + ".jpg").source("TEST")
                .perceptualHash(hash).centerHash(hash).quality(60)
                .width(width).height(height).algorithmVersion(PerceptualHash.ALGORITHM_VERSION).build();
    }
}
