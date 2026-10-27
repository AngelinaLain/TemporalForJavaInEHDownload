package com.checker.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;

/**
 * 把 ComicInfo.xml 写入现有漫画压缩包，让 Komga 扫描时识别标题、作者和合集。
 * 先写临时归档并原样复制已有压缩条目，再替换原文件，避免重新压缩大文件或留下写到一半的归档。
 */
@Slf4j
public final class ComicInfoInjector {

    public static final String COMIC_INFO_ENTRY = "ComicInfo.xml";

    private ComicInfoInjector() {
    }

    /**
     * 原地注入 ComicInfo.xml（先写临时文件再原子替换）。
     *
     * @param archive 待注入的漫画压缩包（zip / cbz）
     * @param info    元数据
     */
    public static void inject(Path archive, ComicInfo info) throws IOException {
        Path tmp = archive.resolveSibling(archive.getFileName() + ".inject.tmp");
        byte[] xml = info.toXml().getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipFile source = new ZipFile(archive.toFile());
                 ZipArchiveOutputStream zos = new ZipArchiveOutputStream(tmp)) {
                // 1. 最前面插入 ComicInfo.xml
                ZipArchiveEntry comicInfoEntry = new ZipArchiveEntry(COMIC_INFO_ENTRY);
                zos.putArchiveEntry(comicInfoEntry);
                zos.write(xml);
                zos.closeArchiveEntry();

                // 2. 原样复制压缩数据，跳过旧 ComicInfo，避免大文件解压后再以 STORED 写入而膨胀。
                source.copyRawEntries(zos,
                        entry -> !COMIC_INFO_ENTRY.equalsIgnoreCase(entry.getName()));
                log.info("✅ ComicInfo.xml 注入完成，已原样复制原压缩条目");
            }

            try {
                Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
