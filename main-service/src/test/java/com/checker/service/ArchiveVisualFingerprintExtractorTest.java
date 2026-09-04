package com.checker.service;

import com.checker.dto.GalleryPageFingerprint;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveVisualFingerprintExtractorTest {
    private final ArchiveVisualFingerprintExtractor extractor = new ArchiveVisualFingerprintExtractor();

    @Test
    void samplesLargeArchiveWithoutExtractingFilesToDisk() throws Exception {
        byte[] archive = archiveWithPages(24);

        List<GalleryPageFingerprint> fingerprints = extractor.extract(
                new ByteArrayInputStream(archive), 99L, 24);

        assertEquals(16, fingerprints.size());
        assertEquals(0, fingerprints.get(0).getPageIndex());
        assertEquals(23, fingerprints.get(fingerprints.size() - 1).getPageIndex());
        assertTrue(fingerprints.stream().allMatch(item -> "ARCHIVE".equals(item.getSource())));
    }

    private byte[] archiveWithPages(int count) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("ComicInfo.xml"));
            zip.write("<ComicInfo/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int index = 0; index < count; index++) {
                BufferedImage image = new BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, 120, 180);
                graphics.setColor(Color.BLACK);
                graphics.drawRect(5 + index % 20, 10, 80, 120);
                graphics.dispose();
                ByteArrayOutputStream page = new ByteArrayOutputStream();
                ImageIO.write(image, "png", page);
                zip.putNextEntry(new ZipEntry(String.format("%03d.png", index)));
                zip.write(page.toByteArray());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
