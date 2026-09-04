package com.checker.service;

import com.checker.common.PerceptualHash;
import com.checker.dto.GalleryPageFingerprint;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ArchiveVisualFingerprintExtractor {
    private static final int SAMPLE_COUNT = 16;

    public List<GalleryPageFingerprint> extract(InputStream archive, Long gid, Integer expectedPages) throws IOException {
        Set<Integer> selected = sampleIndexes(expectedPages, SAMPLE_COUNT);
        List<GalleryPageFingerprint> result = new ArrayList<>();
        int imageIndex = 0;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !isImage(entry.getName())) {
                    zip.closeEntry();
                    continue;
                }
                boolean shouldHash = selected.isEmpty() ? imageIndex < SAMPLE_COUNT : selected.contains(imageIndex);
                if (shouldHash) {
                    GalleryPageFingerprint fingerprint = PerceptualHash.fingerprint(
                            new NonClosingInputStream(zip), gid, imageIndex, entry.getName(), "ARCHIVE");
                    if (fingerprint != null) result.add(fingerprint);
                }
                zip.closeEntry();
                imageIndex++;
            }
        }
        return result;
    }

    private Set<Integer> sampleIndexes(Integer pageCount, int limit) {
        if (pageCount == null || pageCount <= 0) return Set.of();
        int count = Math.min(pageCount, limit);
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        if (count == 1) {
            indexes.add(0);
            return indexes;
        }
        for (int i = 0; i < count; i++) {
            indexes.add((int) Math.round(i * (pageCount - 1D) / (count - 1D)));
        }
        return indexes;
    }

    private boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".jfif") || lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // ImageIO may close its wrapper; the surrounding ZIP stream must stay open.
        }
    }
}
