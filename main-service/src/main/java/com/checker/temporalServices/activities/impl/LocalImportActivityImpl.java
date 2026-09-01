package com.checker.temporalServices.activities.impl;

import com.checker.common.ComicInfo;
import com.checker.common.ComicInfoInjector;
import com.checker.common.Constants;
import com.checker.common.EhNetworkClient;
import com.checker.common.ErrorType;
import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import com.checker.service.SynologyUploadService;
import com.checker.temporalServices.activities.LocalImportActivity;
import cn.hutool.core.util.StrUtil;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 本地导入实现：下载 → ComicInfo 注入 → 重命名 .cbz → 上传群晖 → 更新库记录。
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = Constants.TASK_QUEUE)
public class LocalImportActivityImpl implements LocalImportActivity {

    private static final long HEARTBEAT_INTERVAL_MS = 15_000;
    private static final long DATABASE_PROGRESS_INTERVAL_MS = 3_000;
    private static final long LOCK_WAIT_TIMEOUT_MS = 4 * 60_000;
    private static final long MAX_UNCOMPRESSED_ARCHIVE_BYTES = 20L * 1024 * 1024 * 1024;

    @Autowired
    private EhNetworkClient ehNetworkClient;

    @Autowired
    private EhGalleriesMapper galleriesMapper;

    @Autowired
    private SynologyUploadService uploadService;

    @Autowired
    private EhNetworkConfig netConfig;

    @Override
    public void localDownloadAndImport(String downloadUrl, Long gid, Double sizeMb) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        ctx.heartbeat("加载画廊元数据: " + gid);

        EhGalleriesEntity gallery = galleriesMapper.selectById(gid);
        if (gallery == null) {
            throw ApplicationFailure.newFailure("画廊记录不存在: " + gid, ErrorType.SYNOLOGY_API_ERROR.getCode());
        }

        Path workDir = resolveTempDir().resolve("gallery-" + gid);
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw ApplicationFailure.newFailure("创建本地下载工作目录失败: " + e.getMessage(),
                    ErrorType.SYNOLOGY_DOWNLOAD_ERROR.getCode());
        }

        Path zipFile = workDir.resolve("archive.zip");
        String targetFilename = buildTargetFilename(gid, gallery.getTitle());
        Path cbzFile = workDir.resolve("final.cbz");
        Path metadataFingerprintFile = workDir.resolve("comicinfo.sha256");
        Path lockFile = workDir.resolve(".lock");
        boolean succeeded = false;

        try (FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquireWorkLock(lockChannel, ctx, gid)) {
            ComicInfo comicInfo = buildComicInfo(gallery);
            String metadataFingerprint = sha256(comicInfo.toXml());

            // 已完成的本地 CBZ 只有在 ZIP 深度校验通过后才允许直接重试上传。
            if (Files.isRegularFile(cbzFile)) {
                try {
                    verifyArchive(cbzFile, true, heartbeatProgress(ctx, gid, "校验本地 CBZ", false));
                } catch (IOException invalidCbz) {
                    log.warn("本地 CBZ 缓存无效，将重新下载, GID: {}, 原因: {}", gid, invalidCbz.getMessage());
                    cleanupQuietly(cbzFile);
                    Files.deleteIfExists(metadataFingerprintFile);
                }
            }

            if (!Files.isRegularFile(cbzFile)) {
                // 下载完成后、注入前崩溃时 archive.zip 会保留下来；校验通过即可继续后续阶段。
                if (Files.isRegularFile(zipFile)) {
                    try {
                        verifyArchive(zipFile, false, heartbeatProgress(ctx, gid, "校验下载缓存", false));
                        log.info("⏭️ 检测到完整下载缓存，跳过网络下载, GID: {}", gid);
                    } catch (IOException invalidZip) {
                        log.warn("下载缓存无效，清空后重新下载, GID: {}, 原因: {}", gid, invalidZip.getMessage());
                        cleanupDownloadCache(zipFile);
                    }
                }

                if (!Files.isRegularFile(zipFile)) {
                    ctx.heartbeat("开始本地下载, 预估 " + sizeMb + " MB: " + gid);
                    long bytes = ehNetworkClient.downloadWithResume(downloadUrl, zipFile,
                            heartbeatProgress(ctx, gid, "本地下载", true));
                    updateDownloadedBytesBestEffort(gid, bytes);
                    log.info("✅ 本地下载完成, GID: {}, 字节数: {}", gid, bytes);
                    try {
                        verifyArchive(zipFile, false, heartbeatProgress(ctx, gid, "校验下载文件", false));
                    } catch (IOException verifyEx) {
                        cleanupDownloadCache(zipFile);
                        throw new IOException("下载文件损坏（ZIP/CRC 校验失败）: " + verifyEx.getMessage(), verifyEx);
                    }
                }

                ctx.heartbeat("注入 ComicInfo.xml: " + gid);
                ComicInfoInjector.inject(zipFile, comicInfo);
                moveAtomically(zipFile, cbzFile);
                Files.writeString(metadataFingerprintFile, metadataFingerprint, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                verifyArchive(cbzFile, true, heartbeatProgress(ctx, gid, "校验注入结果", false));
            } else if (!metadataFingerprint.equals(readFingerprint(metadataFingerprintFile))) {
                // 元数据/简介在上次上传失败后发生变化时，只重新注入，不重复网络下载。
                ctx.heartbeat("刷新 ComicInfo.xml: " + gid);
                ComicInfoInjector.inject(cbzFile, comicInfo);
                Files.writeString(metadataFingerprintFile, metadataFingerprint, StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                verifyArchive(cbzFile, true, heartbeatProgress(ctx, gid, "校验元数据刷新结果", false));
            } else {
                log.info("⏭️ 检测到完整且元数据一致的 CBZ，直接重试上传, GID: {}", gid);
            }

            ctx.heartbeat("上传到群晖: " + targetFilename);
            try {
                uploadService.upload(cbzFile, targetFilename,
                        heartbeatProgress(ctx, gid, "上传到群晖", false));
            } catch (ActivityCompletionException e) {
                throw e;
            } catch (ApplicationFailure e) {
                throw e;
            } catch (Exception e) {
                throw ApplicationFailure.newFailure("上传群晖失败: " + e.getMessage(),
                        ErrorType.SYNOLOGY_API_ERROR.getCode());
            }

            EhGalleriesEntity update = new EhGalleriesEntity();
            update.setGid(gid);
            update.setFilename(targetFilename);
            galleriesMapper.updateById(update);

            succeeded = true;
            log.info("🎉 本地导入完成, GID: {}, 文件: {}", gid, targetFilename);
        } catch (ActivityCompletionException e) {
            throw e;
        } catch (ApplicationFailure e) {
            throw e;
        } catch (IOException e) {
            log.error("本地下载/注入失败, GID: {}", gid, e);
            throw ApplicationFailure.newFailure("本地下载/注入失败: " + e.getMessage(),
                    ErrorType.SYNOLOGY_DOWNLOAD_ERROR.getCode());
        } catch (Exception e) {
            log.error("本地导入失败, GID: {}", gid, e);
            throw ApplicationFailure.newFailure("本地导入失败: " + e.getMessage(),
                    ErrorType.SYNOLOGY_API_ERROR.getCode());
        } finally {
            if (succeeded) {
                cleanupWorkDirectory(workDir, zipFile, cbzFile, metadataFingerprintFile, lockFile);
            }
        }
    }

    /**
     * 由画廊元数据构建 ComicInfo：Series 沿用目标系列，作者取 artist/group 标签，
     * 其余标签写入 Genre（Komga 映射为 book tags）。
     */
    private ComicInfo buildComicInfo(EhGalleriesEntity gallery) {
        List<String> writers = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        if (gallery.getTags() != null) {
            for (String tag : gallery.getTags()) {
                if (tag.startsWith("artist:") || tag.startsWith("group:")) {
                    writers.add(tag.substring(tag.indexOf(':') + 1));
                } else {
                    tags.add(tag);
                }
            }
        }
        return ComicInfo.builder()
                .title(gallery.getTitle())
                .series(Constants.KOMGA_TARGET_SERIES)
                .summary(gallery.getSummary())
                .writers(writers)
                .tags(tags)
                .build();
    }

    /**
     * 进度回调同时承担 Temporal 心跳与可选的数据库进度更新。数据库展示失败不应中断下载，
     * 但 heartbeat 抛出的取消/超时异常必须继续传播，让旧 Activity 尽快停止。
     */
    private LongConsumer heartbeatProgress(ActivityExecutionContext ctx, Long gid, String phase,
                                           boolean updateDatabase) {
        AtomicLong lastHeartbeat = new AtomicLong(0);
        AtomicLong lastDatabaseUpdate = new AtomicLong(0);
        return bytes -> {
            long now = System.currentTimeMillis();
            long previousHeartbeat = lastHeartbeat.get();
            if (now - previousHeartbeat >= HEARTBEAT_INTERVAL_MS
                    && lastHeartbeat.compareAndSet(previousHeartbeat, now)) {
                ctx.heartbeat(phase + ": " + bytes + " bytes, GID: " + gid);
            }
            long previousDatabaseUpdate = lastDatabaseUpdate.get();
            if (updateDatabase && now - previousDatabaseUpdate >= DATABASE_PROGRESS_INTERVAL_MS
                    && lastDatabaseUpdate.compareAndSet(previousDatabaseUpdate, now)) {
                updateDownloadedBytesBestEffort(gid, bytes);
            }
        };
    }

    private void updateDownloadedBytesBestEffort(Long gid, long bytes) {
        try {
            EhGalleriesEntity update = new EhGalleriesEntity();
            update.setGid(gid);
            update.setDownloadedBytes(bytes);
            galleriesMapper.updateById(update);
        } catch (RuntimeException e) {
            log.warn("下载进度写库失败但不影响下载, GID: {}, 原因: {}", gid, e.getMessage());
        }
    }

    private FileLock acquireWorkLock(FileChannel channel, ActivityExecutionContext ctx, Long gid) throws IOException {
        long deadline = System.currentTimeMillis() + LOCK_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // 同 JVM 中另一个仍在退出的 Activity 持有锁，继续等待并发送心跳。
            }
            ctx.heartbeat("等待本地下载缓存锁: " + gid);
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("等待本地下载缓存锁时被中断");
            }
        }
        throw new IOException("等待本地下载缓存锁超时: " + gid);
    }

    /** 完整读取每个条目，触发 ZIP CRC 校验，并确认归档中至少包含一张图片。 */
    private void verifyArchive(Path archive, boolean requireComicInfo, LongConsumer progress) throws IOException {
        long archiveSize = Files.size(archive);
        if (archiveSize <= 0) {
            throw new IOException("归档文件为空");
        }
        // 先校验中央目录可读，再顺序读完所有条目以触发 CRC 检查。
        try (ZipFile ignored = new ZipFile(archive.toFile())) {
            // constructor validates the central directory
        }

        long ratioLimit = archiveSize > Long.MAX_VALUE / 200 ? Long.MAX_VALUE : archiveSize * 200;
        long expandedLimit = Math.min(MAX_UNCOMPRESSED_ARCHIVE_BYTES, Math.max(512L * 1024 * 1024, ratioLimit));
        long expandedBytes = 0;
        int entries = 0;
        int images = 0;
        boolean comicInfoFound = false;
        byte[] buffer = new byte[128 * 1024];
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                String name = entry.getName();
                if (ComicInfoInjector.COMIC_INFO_ENTRY.equalsIgnoreCase(name)) {
                    comicInfoFound = true;
                }
                if (isImageEntry(name)) {
                    images++;
                }
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > expandedLimit) {
                        throw new IOException("归档解压后体积异常，疑似压缩炸弹");
                    }
                    progress.accept(expandedBytes);
                }
                zis.closeEntry();
            }
        }
        if (entries == 0 || images == 0) {
            throw new IOException("归档不包含有效图片条目");
        }
        if (requireComicInfo && !comicInfoFound) {
            throw new IOException("归档缺少 ComicInfo.xml");
        }
        log.info("📦 ZIP 深度校验通过，{} 个文件条目，{} 张图片，读取 {} 字节",
                entries, images, expandedBytes);
    }

    private static boolean isImageEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".avif")
                || lower.endsWith(".bmp") || lower.endsWith(".jfif") || lower.endsWith(".jxl")
                || lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String readFingerprint(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupDownloadCache(Path zipFile) throws IOException {
        Files.deleteIfExists(zipFile);
        Files.deleteIfExists(zipFile.resolveSibling(zipFile.getFileName() + ".part"));
        Files.deleteIfExists(zipFile.resolveSibling(zipFile.getFileName() + ".part.meta"));
        Files.deleteIfExists(zipFile.resolveSibling(zipFile.getFileName() + ".inject.tmp"));
    }

    /**
     * 构建安全文件名：[gid] 清理后的标题.cbz，规避群晖「文件夹/文件名不合法或超长」问题。
     */
    private String buildTargetFilename(Long gid, String title) {
        String safeTitle = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        safeTitle = safeTitle.replaceAll("[. ]+$", "");
        int fixedBytes = ("[" + gid + "] .cbz").getBytes(StandardCharsets.UTF_8).length;
        safeTitle = truncateUtf8(safeTitle, Math.max(32, 240 - fixedBytes));
        return "[" + gid + "] " + safeTitle + ".cbz";
    }

    private static String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            int bytes = next.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) break;
            result.append(next);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private Path resolveTempDir() {
        String dir = netConfig.getDownload() != null ? netConfig.getDownload().getTempDir() : null;
        Path path = StrUtil.isNotBlank(dir) ? Path.of(dir) : Path.of(System.getProperty("java.io.tmpdir"));
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            log.warn("创建临时目录失败，回退系统临时目录: {}", e.getMessage());
            path = Path.of(System.getProperty("java.io.tmpdir"));
        }
        return path;
    }

    private void cleanupQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
                Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".part"));
            }
        } catch (IOException ignored) {
            // 忽略临时文件清理失败
        }
    }

    private void cleanupWorkDirectory(Path workDir, Path zipFile, Path cbzFile,
                                      Path metadataFingerprintFile, Path lockFile) {
        cleanupQuietly(zipFile);
        cleanupQuietly(cbzFile);
        cleanupQuietly(metadataFingerprintFile);
        cleanupQuietly(zipFile.resolveSibling(zipFile.getFileName() + ".part.meta"));
        cleanupQuietly(zipFile.resolveSibling(zipFile.getFileName() + ".inject.tmp"));
        cleanupQuietly(cbzFile.resolveSibling(cbzFile.getFileName() + ".inject.tmp"));
        cleanupQuietly(lockFile);
        try {
            Files.deleteIfExists(workDir);
        } catch (IOException e) {
            log.warn("本地工作目录清理失败，将由后续任务复用/清理: {}", workDir);
        }
    }
}
