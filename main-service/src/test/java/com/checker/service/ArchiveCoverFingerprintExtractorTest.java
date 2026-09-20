package com.checker.service;

import com.checker.dto.GalleryPageFingerprint;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArchiveCoverFingerprintExtractorTest {
    private final ArchiveCoverFingerprintExtractor extractor = new ArchiveCoverFingerprintExtractor();

    @Test
    void skipsBrokenFirstImageAndUsesNextDecodablePage() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("ComicInfo.xml"));
            zip.write("<ComicInfo/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("001.jpg"));
            zip.write("not-an-image".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("002.png"));
            zip.write(image());
            zip.closeEntry();
        }

        GalleryPageFingerprint result = extractor.extract(
                new ByteArrayInputStream(archive.toByteArray()), 42L, "sample.cbz");

        assertNotNull(result);
        assertEquals("002.png", result.getPageName());
        assertEquals("ARCHIVE_COVER", result.getSource());
    }

    private byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 240, 320);
            graphics.setColor(Color.BLUE);
            graphics.fillOval(30, 40, 170, 220);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
