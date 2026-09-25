package com.checker.service;

import cn.hutool.core.util.StrUtil;
import com.checker.config.EhNetworkConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/** Direct filesystem access to a Komga library bind-mounted into the process/container. */
@Slf4j
@Component
public class MountedArchiveStorage {
    private final EhNetworkConfig config;

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
