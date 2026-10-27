package com.checker.service;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 直接访问绑定到进程/容器中的Komga库。 */
@Slf4j
@Component
public class MountedArchiveStorage {
    private static final Pattern PREVIEW_GID = Pattern.compile("^\\[(\\d+)](?:\\s|$)");
    private static final int PREVIEW_MAX_ENTRIES = 100_000;
    private static final int PREVIEW_MAX_ARCHIVES = 50_000;
    private static final int PREVIEW_MAX_DEPTH = 16;
    private static final long PREVIEW_INDEX_TTL = TimeUnit.SECONDS.toNanos(30);
    private static final long PREVIEW_SCAN_BUDGET = TimeUnit.SECONDS.toNanos(5);
    private final EhNetworkConfig config;
    private volatile PreviewIndex previewIndex;
    private PreviewIndexFailure previewIndexFailure;

    public MountedArchiveStorage(EhNetworkConfig config) {
        this.config = config;
    }

    public boolean isReadable() {
        Optional<Path> root = configuredRoot();
        return root.isPresent() && Files.isDirectory(root.get()) && Files.isReadable(root.get());
    }

    public boolean isWritable() {
        Optional<Path> root = configuredRoot();
        return root.isPresent() && Files.isDirectory(root.get()) && Files.isReadable(root.get())
                && Files.isWritable(root.get());
    }

    public <T> T read(String relativeDirectory, String filename,
                      SynologyArchiveReader.ArchiveInputFunction<T> function) throws Exception {
        Path archive = resolveExistingArchive(relativeDirectory, filename);
        try (InputStream input = Files.newInputStream(archive)) {
            return function.apply(input);
        }
    }

    /** Read-only random access for paged previews; does not copy or extract the archive. */
    public Optional<Path> findReadableArchive(String relativeDirectory, String filename) throws Exception {
        if (!isReadable() || filename == null || filename.isBlank()) return Optional.empty();
        try {
            Path archive = resolveExistingArchive(relativeDirectory, filename);
            Path root = requireRoot().toRealPath();
            Path real = archive.toRealPath();
            if (!real.startsWith(root)) throw new IllegalArgumentException("归档文件超出挂载根目录");
            return Files.isReadable(real) ? Optional.of(real) : Optional.empty();
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
    }

    /** Read-only preview lookup; legacy placement metadata may lag a collection move or rename. */
    public Optional<Path> findReadableArchive(String relativeDirectory, String filename, Long gid) throws Exception {
        if (gid == null || gid <= 0) throw new IllegalArgumentException("画廊 GID 必须为正整数");
        Path configured = configuredRoot().orElseThrow(() -> new PreviewLookupException(
                "NOT_MOUNTED", "未配置本地归档挂载；请检查 ARCHIVE_MOUNT_PATH 或使用 Komga 图库"));
        if (!Files.isDirectory(configured) || !Files.isReadable(configured)) throw new PreviewLookupException(
                "NOT_READABLE", "本地归档挂载目录不可读；请检查目录挂载与读取权限");
        Path root = configured.toRealPath();
        Path recordedDirectory = resolveDirectory(relativeDirectory);
        if (Files.exists(recordedDirectory) && !recordedDirectory.toRealPath().startsWith(root))
            throw new IllegalArgumentException("归档目录超出挂载根目录");
        if (filename != null && !filename.isBlank()) {
            Path exact = resolveFilename(recordedDirectory, filename);
            var prefix = PREVIEW_GID.matcher(filename);
            if (prefix.find() && !prefix.group(1).equals(gid.toString()))
                throw new IllegalArgumentException("归档文件名与画廊 GID 不一致");
            if (isSupportedArchive(filename) && Files.isRegularFile(exact)) return checkedPreviewPath(root, exact);
            // Pre-series libraries kept unprefixed database filenames directly in their root.
            if (!prefix.reset().find() && !recordedDirectory.equals(configured)) {
                Path legacy = resolveFilename(configured, filename);
                if (isSupportedArchive(filename) && Files.isRegularFile(legacy)) return checkedPreviewPath(root, legacy);
            }
        }
        // A global unique match covers same-directory renames, root archives and migrated series.
        // Never choose an arbitrary historical version when the same GID exists in multiple folders.
        List<Path> matches = previewIndex(root).byGid().getOrDefault(gid.toString(), List.of());
        if (matches.size() > 1) throw new PreviewLookupException(
                "AMBIGUOUS", "同一 GID 匹配到多个本地归档，请在归档同步中核查后重试");
        if (matches.isEmpty()) return Optional.empty();
        Optional<Path> result = checkedPreviewPath(root, matches.get(0));
        if (result.isPresent()) return result;
        // A previously indexed file was moved after the scan; refresh once instead of serving stale paths.
        invalidatePreviewIndex();
        matches = previewIndex(root).byGid().getOrDefault(gid.toString(), List.of());
        if (matches.size() > 1) throw new PreviewLookupException(
                "AMBIGUOUS", "同一 GID 匹配到多个本地归档，请在归档同步中核查后重试");
        return matches.isEmpty() ? Optional.empty() : checkedPreviewPath(root, matches.get(0));
    }

    /** Explicit retries may invalidate the bounded directory snapshot without changing library files. */
    public synchronized void invalidatePreviewIndex() { previewIndex = null; previewIndexFailure = null; }

    private Optional<Path> checkedPreviewPath(Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        Path real = file.toRealPath();
        if (!real.startsWith(root)) throw new IllegalArgumentException("归档文件超出挂载根目录");
        if (!Files.isReadable(real)) throw new PreviewLookupException("NOT_READABLE", "本地归档文件不可读，请检查读取权限");
        return Optional.of(real);
    }

    private synchronized PreviewIndex previewIndex(Path root) throws IOException {
        long now = System.nanoTime();
        if (previewIndex != null && previewIndex.root().equals(root) && now - previewIndex.createdAt() < PREVIEW_INDEX_TTL)
            return previewIndex;
        if (previewIndexFailure != null && previewIndexFailure.root().equals(root)
                && now - previewIndexFailure.createdAt() < PREVIEW_INDEX_TTL)
            throw new PreviewLookupException(previewIndexFailure.code(), previewIndexFailure.message());
        Map<String, List<Path>> matches = new HashMap<>();
        int[] entries = {0}, archives = {0};
        try { Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void checkBudget() throws PreviewLookupException {
                if (++entries[0] > PREVIEW_MAX_ENTRIES || System.nanoTime() - now > PREVIEW_SCAN_BUDGET)
                    throw new PreviewLookupException("SCAN_LIMIT", "本地归档索引超过扫描预算，请修正画廊目录或使用 Komga 图库");
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                checkBudget();
                if (root.relativize(directory).getNameCount() > PREVIEW_MAX_DEPTH)
                    throw new PreviewLookupException("SCAN_LIMIT", "本地归档目录层级过深，请修正画廊目录或使用 Komga 图库");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkBudget();
                // No FOLLOW_LINKS: neither directory cycles nor files outside the mount enter the index.
                if (!attributes.isRegularFile() || !isSupportedArchive(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (++archives[0] > PREVIEW_MAX_ARCHIVES) throw new PreviewLookupException(
                        "SCAN_LIMIT", "本地归档数量超过索引预算，请修正画廊目录或使用 Komga 图库");
                var gid = PREVIEW_GID.matcher(file.getFileName().toString());
                if (gid.find()) {
                    List<Path> paths = matches.computeIfAbsent(gid.group(1), ignored -> new ArrayList<>(2));
                    if (paths.size() < 2) paths.add(file); // Two are enough to prove ambiguity; memory remains bounded.
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw new PreviewLookupException("NOT_READABLE", "本地归档索引遇到不可读目录，请检查挂载权限或使用 Komga 图库");
            }
        }); } catch (IOException failure) {
            String code = failure instanceof PreviewLookupException lookup ? lookup.code() : "NOT_READABLE";
            String message = failure instanceof PreviewLookupException ? failure.getMessage()
                    : "本地归档索引读取失败，请检查挂载权限或使用 Komga 图库";
            previewIndexFailure = new PreviewIndexFailure(root, System.nanoTime(), code, message);
            throw new PreviewLookupException(code, message);
        }
        matches.replaceAll((gid, paths) -> List.copyOf(paths));
        previewIndex = new PreviewIndex(root, System.nanoTime(), Map.copyOf(matches));
        previewIndexFailure = null;
        return previewIndex;
    }

    private record PreviewIndex(Path root, long createdAt, Map<String, List<Path>> byGid) {}
    private record PreviewIndexFailure(Path root, long createdAt, String code, String message) {}

    public static final class PreviewLookupException extends IOException {
        private final String code;
        public PreviewLookupException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public void upload(Path localFile, String relativeDirectory, String filename,
                       LongConsumer progress) throws Exception {
        Path directory = resolveDirectory(relativeDirectory);
        Files.createDirectories(directory);
        Path target = resolveFilename(directory, filename);
        Path temporary = resolveFilename(directory, "." + filename + ".uploading");
        try {
            long copied = 0;
            try (InputStream input = Files.newInputStream(localFile);
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    copied += read;
                    progress.accept(copied);
                }
            }
            moveReplacing(temporary, target);
            deleteOtherGidArchives(directory, filename);
            log.info("✅ 挂载目录写入成功: {}", target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public List<String> listArchives() throws Exception {
        return listArchives("");
    }

    public List<String> listArchives(String relativeDirectory) throws Exception {
        Path directory = resolveDirectory(relativeDirectory);
        if (Files.notExists(directory)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(MountedArchiveStorage::isSupportedArchive).toList();
        }
    }

    public void rename(String sourceFilename, String targetFilename) throws Exception {
        Path root = requireRoot();
        moveReplacing(resolveFilename(root, sourceFilename), resolveFilename(root, targetFilename));
    }

    public void delete(String relativeDirectory, String filename) throws Exception {
        Files.delete(resolveExistingArchive(relativeDirectory, filename));
    }

    public void deleteExact(String relativeDirectory, String filename) throws Exception {
        Files.deleteIfExists(resolveFilename(resolveDirectory(relativeDirectory), filename));
    }

    private Path resolveExistingArchive(String relativeDirectory, String filename) throws Exception {
        Path directory = resolveDirectory(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(directory.toString(), null, "归档目录不存在");
        }
        Path exact = resolveFilename(directory, filename);
        if (Files.isRegularFile(exact)) return exact;

        List<String> names;
        try (Stream<Path> entries = Files.list(directory)) {
            names = entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList();
        }
        String resolved = SynologyArchiveReader.selectGidArchive(filename, names)
                .orElseThrow(() -> new NoSuchFileException(
                        exact.toString(), null, "归档文件不存在，且未找到同 GID 的归档"));
        return resolveFilename(directory, resolved);
    }

    private void deleteOtherGidArchives(Path directory, String keepFilename) throws Exception {
        List<Path> stale;
        try (Stream<Path> entries = Files.list(directory)) {
            stale = entries.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase(keepFilename))
                    .filter(path -> SynologyArchiveReader.isArchiveForSameGid(
                            keepFilename, path.getFileName().toString()))
                    .toList();
        }
        for (Path path : stale) {
            Files.deleteIfExists(path);
            log.info("🧹 删除同 GID 的旧归档: {}", path);
        }
    }

    private Path resolveDirectory(String relativeDirectory) {
        Path root = requireRoot();
        String relative = relativeDirectory == null ? "" : relativeDirectory.replace('\\', '/');
        if (relative.startsWith("/") || relative.endsWith("/") || relative.contains("//")
                || relative.equals("..") || relative.contains("../") || relative.contains("/..")) {
            throw new IllegalArgumentException("非法系列目录");
        }
        Path resolved = relative.isBlank() ? root : root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("系列目录超出挂载根目录");
        return resolved;
    }

    private Path resolveFilename(Path directory, String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("非法归档文件名");
        }
        Path resolved = directory.resolve(filename).normalize();
        if (!resolved.getParent().equals(directory)) throw new IllegalArgumentException("归档文件超出目标目录");
        return resolved;
    }

    private Optional<Path> configuredRoot() {
        String value = config.getArchiveStorage() == null ? null : config.getArchiveStorage().getMountPath();
        if (StrUtil.isBlank(value)) return Optional.empty();
        return Optional.of(Path.of(value).toAbsolutePath().normalize());
    }

    private Path requireRoot() {
        return configuredRoot().orElseThrow(() -> new IllegalStateException("未配置 ARCHIVE_MOUNT_PATH"));
    }

    private static void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isSupportedArchive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".cbz") || lower.endsWith(".zip")) && !lower.endsWith(".uploading");
    }
}
