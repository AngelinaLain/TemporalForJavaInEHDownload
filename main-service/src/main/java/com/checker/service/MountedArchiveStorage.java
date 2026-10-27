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

    /** 检查配置的归档根目录是否存在且可读。 */
    public boolean isReadable() {
        Optional<Path> root = configuredRoot();
        return root.isPresent() && Files.isDirectory(root.get()) && Files.isReadable(root.get());
    }

    /** 检查归档根目录是否同时具备读取与写入条件。 */
    public boolean isWritable() {
        Optional<Path> root = configuredRoot();
        return root.isPresent() && Files.isDirectory(root.get()) && Files.isReadable(root.get())
                && Files.isWritable(root.get());
    }

    /** 在挂载目录中打开已定位的归档，并在处理结束时关闭输入流。 */
    public <T> T read(String relativeDirectory, String filename,
                      SynologyArchiveReader.ArchiveInputFunction<T> function) throws Exception {
        Path archive = resolveExistingArchive(relativeDirectory, filename);
        try (InputStream input = Files.newInputStream(archive)) {
            return function.apply(input);
        }
    }

    /** 为分页预览定位可读归档，只返回文件位置，不复制整本归档或解压到磁盘。 */
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

    /** 按记录路径和稳定 GID 查找预览归档，兼容合集移动、改名后数据库位置尚未更新的旧记录。 */
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
            // 兼容还没有系列目录时的布局：未带 GID 前缀的历史文件可能仍放在图库根目录。
            if (!prefix.reset().find() && !recordedDirectory.equals(configured)) {
                Path legacy = resolveFilename(configured, filename);
                if (isSupportedArchive(filename) && Files.isRegularFile(legacy)) return checkedPreviewPath(root, legacy);
            }
        }
        // 缓存全库 GID 索引，兼顾同目录改名、旧根目录和系列迁移；多个目录同 GID 时不随意挑一个版本。
        List<Path> matches = previewIndex(root).byGid().getOrDefault(gid.toString(), List.of());
        if (matches.size() > 1) throw new PreviewLookupException(
                "AMBIGUOUS", "同一 GID 匹配到多个本地归档，请在归档同步中核查后重试");
        if (matches.isEmpty()) return Optional.empty();
        Optional<Path> result = checkedPreviewPath(root, matches.get(0));
        if (result.isPresent()) return result;
        // 索引中的文件可能刚被移动，重新建立一次索引，而不是继续返回已经失效的位置。
        invalidatePreviewIndex();
        matches = previewIndex(root).byGid().getOrDefault(gid.toString(), List.of());
        if (matches.size() > 1) throw new PreviewLookupException(
                "AMBIGUOUS", "同一 GID 匹配到多个本地归档，请在归档同步中核查后重试");
        return matches.isEmpty() ? Optional.empty() : checkedPreviewPath(root, matches.get(0));
    }

    /** 主动重试时丢弃目录快照，仅重新检测，不改动图库文件。 */
    public synchronized void invalidatePreviewIndex() { previewIndex = null; previewIndexFailure = null; }

    /** 再次核对真实文件仍位于挂载根目录内且可读，防止符号链接越界。 */
    private Optional<Path> checkedPreviewPath(Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        Path real = file.toRealPath();
        if (!real.startsWith(root)) throw new IllegalArgumentException("归档文件超出挂载根目录");
        if (!Files.isReadable(real)) throw new PreviewLookupException("NOT_READABLE", "本地归档文件不可读，请检查读取权限");
        return Optional.of(real);
    }

    /** 合并并发的目录索引请求，限制扫描时间、层级和条目数，完整成功后才缓存结果。 */
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
                // 扫描不跟随符号链接，避免目录循环以及把挂载根目录以外的文件加入索引。
                if (!attributes.isRegularFile() || !isSupportedArchive(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (++archives[0] > PREVIEW_MAX_ARCHIVES) throw new PreviewLookupException(
                        "SCAN_LIMIT", "本地归档数量超过索引预算，请修正画廊目录或使用 Komga 图库");
                var gid = PREVIEW_GID.matcher(file.getFileName().toString());
                if (gid.find()) {
                    List<Path> paths = matches.computeIfAbsent(gid.group(1), ignored -> new ArrayList<>(2));
                    if (paths.size() < 2) paths.add(file); // 两条路径已足够证明有歧义，无需为重复版本继续占用内存。
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

    /** 携带可供界面分类展示的定位失败代码，不把实际服务器路径暴露给前端。 */
    public static final class PreviewLookupException extends IOException {
        private final String code;
        public PreviewLookupException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** 先上传到临时文件，再切换为正式文件名，完成后清理同 GID 旧归档。 */
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

    /** 列出指定目录中的受支持归档文件，不返回上传中的临时文件。 */
    public List<String> listArchives() throws Exception {
        return listArchives("");
    }

    /** 列出指定目录中的受支持归档文件，不返回上传中的临时文件。 */
    public List<String> listArchives(String relativeDirectory) throws Exception {
        Path directory = resolveDirectory(relativeDirectory);
        if (Files.notExists(directory)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(MountedArchiveStorage::isSupportedArchive).toList();
        }
    }

    /** 在挂载根目录中改名，路径仍通过文件名检查。 */
    public void rename(String sourceFilename, String targetFilename) throws Exception {
        Path root = requireRoot();
        moveReplacing(resolveFilename(root, sourceFilename), resolveFilename(root, targetFilename));
    }

    /** 定位指定归档后删除，兼容同 GID 的历史改名。 */
    public void delete(String relativeDirectory, String filename) throws Exception {
        Files.delete(resolveExistingArchive(relativeDirectory, filename));
    }

    /** 只删除记录指定的确切文件名，不按 GID 扩大删除范围。 */
    public void deleteExact(String relativeDirectory, String filename) throws Exception {
        Files.deleteIfExists(resolveFilename(resolveDirectory(relativeDirectory), filename));
    }

    /** 优先使用准确路径，缺失时只接受同 GID 的唯一历史归档。 */
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

    /** 把相对目录限制在配置根目录内，拒绝目录穿越。 */
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

    /** 优先原子切换文件；文件系统不支持时才使用普通替换。 */
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
