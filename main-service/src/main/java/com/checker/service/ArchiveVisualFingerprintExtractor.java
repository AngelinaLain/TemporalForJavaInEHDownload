package com.checker.service;

import com.checker.common.PerceptualHash;
import com.checker.dto.GalleryPageFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ArchiveVisualFingerprintExtractor {
    private static final Logger log = LoggerFactory.getLogger(ArchiveVisualFingerprintExtractor.class);
    private static final int SAMPLE_COUNT = 16;
    private final TaskExecutor fingerprintExecutor;

    public ArchiveVisualFingerprintExtractor(
            @Qualifier("visualFingerprintExecutor") TaskExecutor fingerprintExecutor) {
        this.fingerprintExecutor = fingerprintExecutor;
    }

    public List<GalleryPageFingerprint> extract(InputStream archive, Long gid, Integer expectedPages) throws IOException {
        Set<Integer> selected = sampleIndexes(expectedPages, SAMPLE_COUNT);
        List<PendingFingerprint> pending = new ArrayList<>();
        int imageIndex = 0;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !isImage(entry.getName())) {
                    zip.closeEntry();
                    continue;
                }
                boolean shouldHash = isLikelyCover(entry.getName())
                        || (selected.isEmpty() ? imageIndex < SAMPLE_COUNT : selected.contains(imageIndex));
                if (shouldHash) {
                    byte[] image = zip.readAllBytes();
                    int pageIndex = imageIndex;
                    String pageName = entry.getName();
                    CompletableFuture<GalleryPageFingerprint> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return PerceptualHash.fingerprint(new ByteArrayInputStream(image),
                                    gid, pageIndex, pageName, "ARCHIVE");
                        } catch (IOException failure) {
                            throw new CompletionException(failure);
                        }
                    }, fingerprintExecutor);
                    pending.add(new PendingFingerprint(pageIndex, pageName, future));
                }
                zip.closeEntry();
                imageIndex++;
            }
        }
        List<GalleryPageFingerprint> result = new ArrayList<>(pending.size());
        Throwable firstFailure = null;
        for (PendingFingerprint item : pending) {
            try {
                GalleryPageFingerprint fingerprint = item.future().join();
                if (fingerprint != null) result.add(fingerprint);
            } catch (CompletionException failure) {
                Throwable cause = rootCause(failure);
                if (cause instanceof Error error) throw error;
                if (firstFailure == null) firstFailure = cause;
                log.warn("跳过无法生成视觉指纹的采样页, GID: {}, 页码: {}, 文件: {}, 原因: {}: {}",
                        gid, item.pageIndex(), item.pageName(), cause.getClass().getSimpleName(), cause.getMessage());
            }
        }
        if (result.isEmpty() && !pending.isEmpty() && firstFailure != null) {
            throw new IOException("所有采样页的视觉指纹均计算失败: " + describe(firstFailure), firstFailure);
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

    static boolean isLikelyCover(String name) {
        if (name == null) return false;
        String normalized = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = basename.lastIndexOf('.');
        if (dot > 0) basename = basename.substring(0, dot);
        return basename.equals("cover") || basename.equals("front")
                || basename.startsWith("cover_") || basename.startsWith("cover-");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current.getClass() == RuntimeException.class)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record PendingFingerprint(int pageIndex, String pageName,
                                      CompletableFuture<GalleryPageFingerprint> future) {
    }

}
