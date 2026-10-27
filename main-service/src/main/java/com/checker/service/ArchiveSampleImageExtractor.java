package com.checker.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 从归档顺序读取指定图片页，限制单页大小，为视觉模型提供少量采样图片。 */
@Component
public class ArchiveSampleImageExtractor {
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    /** 只读取要求的采样页，凑齐后停止扫描；单张图片超过限制时明确失败。 */
    public Map<Integer, SampleImage> extract(InputStream archive, Set<Integer> pageIndexes) throws IOException {
        Map<Integer, SampleImage> result = new LinkedHashMap<>();
        if (pageIndexes == null || pageIndexes.isEmpty()) return result;
        int pageIndex = 0;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && result.size() < pageIndexes.size()) {
                if (!entry.isDirectory() && isImage(entry.getName())) {
                    if (pageIndexes.contains(pageIndex)) {
                        byte[] bytes = zip.readNBytes(MAX_IMAGE_BYTES + 1);
                        if (bytes.length > MAX_IMAGE_BYTES) {
                            throw new IOException("采样页超过 20MB: " + entry.getName());
                        }
                        result.put(pageIndex, new SampleImage(pageIndex, entry.getName(), mimeType(entry.getName()), bytes));
                    }
                    pageIndex++;
                }
                zip.closeEntry();
            }
        }
        return result;
    }

    private static boolean isImage(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif")
                || lower.endsWith(".tif") || lower.endsWith(".tiff") || lower.endsWith(".jfif");
    }

    private static String mimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return "image/tiff";
        return "image/jpeg";
    }

    /** 记录采样页在归档中的位置、文件名、格式及图片内容。 */
    public record SampleImage(int pageIndex, String pageName, String mimeType, byte[] bytes) {
    }
}
