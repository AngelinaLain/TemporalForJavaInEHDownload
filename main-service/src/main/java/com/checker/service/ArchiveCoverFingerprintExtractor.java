package com.checker.service;

import com.checker.common.PerceptualHash;
import com.checker.dto.GalleryPageFingerprint;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads only enough of a CBZ/ZIP to fingerprint its first decodable page. */
@Component
public class ArchiveCoverFingerprintExtractor {
    private static final int MAX_IMAGE_ATTEMPTS = 5;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    public GalleryPageFingerprint extract(InputStream archive, Long gid, String filename) throws IOException {
        int attempted = 0;
        Throwable firstFailure = null;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && attempted < MAX_IMAGE_ATTEMPTS) {
                if (entry.isDirectory() || !isImage(entry.getName())) {
                    zip.closeEntry();
                    continue;
                }
                attempted++;
                try {
                    byte[] bytes = readBounded(zip, MAX_IMAGE_BYTES);
                    GalleryPageFingerprint result = PerceptualHash.fingerprint(
                            new ByteArrayInputStream(bytes), gid, 0, entry.getName(), "ARCHIVE_COVER");
                    if (result != null) return result;
                } catch (IOException | RuntimeException failure) {
                    if (firstFailure == null) firstFailure = failure;
                } finally {
                    zip.closeEntry();
                }
            }
        }
        String reason = firstFailure == null ? "压缩包前几项中没有可识别图片" : describe(firstFailure);
        throw new IOException("无法读取封面 " + filename + ": " + reason, firstFailure);
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 1024 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("封面图片超过 20MB 限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isImage(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".jfif") || lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
