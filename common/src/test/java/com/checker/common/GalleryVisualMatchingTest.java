package com.checker.common;

import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GalleryVisualMatch;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void hashesSameDrawingAtDifferentResolutionClosely() throws Exception {
        GalleryPageFingerprint small = hashDrawing(1L, 320, 480);
        GalleryPageFingerprint large = hashDrawing(2L, 960, 1440);

        assertNotNull(small);
        assertNotNull(large);
        assertTrue(PerceptualHash.distance(small.getPerceptualHash(), large.getPerceptualHash()) <= 8);
    }

    private GalleryPageFingerprint hashDrawing(Long gid, int width, int height) throws Exception {
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
        return PerceptualHash.fingerprint(new ByteArrayInputStream(bytes.toByteArray()), gid, 0, "page.png", "TEST");
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
