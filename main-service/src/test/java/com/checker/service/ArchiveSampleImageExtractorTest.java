package com.checker.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchiveSampleImageExtractorTest {
    @Test
    void extractsRequestedImageIndexesIgnoringOtherEntries() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, "ComicInfo.xml", new byte[]{9});
            add(zip, "001.jpg", new byte[]{1});
            add(zip, "002.png", new byte[]{2});
            add(zip, "003.webp", new byte[]{3});
            zip.finish();
            archive = output.toByteArray();
        }
        Map<Integer, ArchiveSampleImageExtractor.SampleImage> result =
                new ArchiveSampleImageExtractor().extract(new ByteArrayInputStream(archive), Set.of(0, 2));
        assertEquals(Set.of(0, 2), result.keySet());
        assertArrayEquals(new byte[]{1}, result.get(0).bytes());
        assertEquals("image/webp", result.get(2).mimeType());
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
