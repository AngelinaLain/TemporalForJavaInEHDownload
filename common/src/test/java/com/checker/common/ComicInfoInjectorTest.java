package com.checker.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验 ComicInfo 注入：不破坏原有压缩包条目，且插入的 ComicInfo.xml 内容正确。
 */
class ComicInfoInjectorTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsComicInfoWithoutCorruptingExistingEntries() throws Exception {
        Path archive = tempDir.resolve("test.zip");
        byte[] page1 = "fake page one".getBytes(StandardCharsets.UTF_8);
        byte[] page2 = "fake page two with more content".getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
            zos.putNextEntry(new ZipEntry("1.jpg"));
            zos.write(page1);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("2.jpg"));
            zos.write(page2);
            zos.closeEntry();
        }

        long originalCompressedSize;
        int originalMethod;
        try (ZipFile original = new ZipFile(archive.toFile())) {
            ZipEntry originalPage = original.getEntry("1.jpg");
            originalCompressedSize = originalPage.getCompressedSize();
            originalMethod = originalPage.getMethod();
        }

        ComicInfo info = ComicInfo.builder()
                .title("Test <Gallery> & 中文")
                .series("N8N_Update")
                .summary("简介 summary")
                .writers(List.of("artist-aaa", "group-bbb"))
                .tags(List.of("language:chinese", "female:stockings"))
                .build();

        ComicInfoInjector.inject(archive, info);

        try (ZipFile result = new ZipFile(archive.toFile())) {
            // ComicInfo.xml 应为第一个条目
            var entries = result.entries();
            assertTrue(entries.hasMoreElements());
            ZipEntry first = entries.nextElement();
            assertEquals(ComicInfoInjector.COMIC_INFO_ENTRY, first.getName());

            String xml = readEntry(result, first);
            assertTrue(xml.contains("Test &lt;Gallery&gt; &amp; 中文"), "标题应做 XML 转义: " + xml);
            assertTrue(xml.contains("<Series>N8N_Update</Series>"));
            assertTrue(xml.contains("<Writer>artist-aaa, group-bbb</Writer>"));

            // 原条目内容完好
            assertEquals("fake page one", readEntry(result, result.getEntry("1.jpg")));
            assertEquals("fake page two with more content", readEntry(result, result.getEntry("2.jpg")));
            // 原始压缩数据应 raw-copy，不应解压后以 STORED 方式写回造成体积膨胀。
            assertEquals(originalMethod, result.getEntry("1.jpg").getMethod());
            assertEquals(originalCompressedSize, result.getEntry("1.jpg").getCompressedSize());
        }
    }

    @Test
    void emptyTagsProduceMinimalXml() {
        ComicInfo info = ComicInfo.builder().title("only title").build();
        String xml = info.toXml();
        assertTrue(xml.contains("<Title>only title</Title>"));
        assertFalse(xml.contains("<Genre>"), "无标签时不应输出 Genre");
        assertFalse(xml.contains("<Writer>"), "无作者时不应输出 Writer");
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws Exception {
        try (var in = zip.getInputStream(entry);
             var out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
