package com.checker.service;

import com.checker.config.EhNetworkConfig;
import com.checker.entity.EhGalleriesEntity;
import com.checker.mapper.EhGalleriesMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GalleryMediaServiceTest {
    @TempDir Path directory;
    private EhGalleriesMapper mapper;
    private KomgaMediaClient komga;
    private MountedArchiveStorage mounted;
    private EhNetworkConfig config;
    private EhGalleriesEntity gallery;
    private GalleryMediaService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(EhGalleriesMapper.class);
        komga = mock(KomgaMediaClient.class);
        mounted = mock(MountedArchiveStorage.class);
        config = new EhNetworkConfig();
        gallery = new EhGalleriesEntity();
        gallery.setGid(42L);
        gallery.setFilename("[42] Example.cbz");
        gallery.setStoragePath("Example");
        when(mapper.getPreviewMetadata(42L)).thenReturn(gallery);
        when(mounted.findReadableArchive(any(), any(), any())).thenReturn(Optional.empty());
        service = new GalleryMediaService(mapper, komga, mounted, config);
    }

    @Test
    @SuppressWarnings("unchecked")
    void komgaPaginationOnlyReadsCachedMetadataAndNeverFetchesImages() throws Exception {
        gallery.setKomgaBookId("book-id");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("book-id")).thenReturn(175);
        Map<String, Object> page = service.page(42L, 2, 20);
        assertEquals("KOMGA", page.get("source"));
        assertEquals(175, page.get("total"));
        List<Map<String, Object>> images = (List<Map<String, Object>>) page.get("images");
        assertEquals(20, images.size());
        assertEquals(21, images.get(0).get("index"));
        assertEquals(40, images.get(19).get("index"));
        assertEquals("/api/dashboard/gallery-media/42/images/21?thumbnail=true", images.get(0).get("thumbnailUrl"));
        assertEquals(9, service.page(42L, 999, 20).get("page"));
        verify(komga, times(1)).pageCount("book-id");
        verify(mapper, times(1)).getPreviewMetadata(42L);
        verify(komga, never()).image(anyString(), anyInt(), anyBoolean());
        verify(komga, never()).cover(anyString());
        verify(mounted, times(1)).findReadableArchive("Example", gallery.getFilename(), 42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void archiveMetadataUsesNaturalOrderAndReadsOnlyRequestedEntry() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pages/10.jpg", "not decoded by metadata paging".getBytes(StandardCharsets.UTF_8));
        byte[] expected = png(120, 60);
        entries.put("pages/2.png", expected);
        entries.put("pages/1.jpg", "invalid image".getBytes(StandardCharsets.UTF_8));
        entries.put("__MACOSX/ignored.png", expected);
        entries.put("ComicInfo.xml", "<ComicInfo/>".getBytes(StandardCharsets.UTF_8));
        Path archive = zip(entries);
        when(mounted.findReadableArchive("Example", gallery.getFilename(), 42L)).thenReturn(Optional.of(archive));
        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("ARCHIVE", page.get("source"));
        assertEquals(3, page.get("total"));
        List<Map<String, Object>> images = (List<Map<String, Object>>) page.get("images");
        assertEquals(List.of("pages/1.jpg", "pages/2.png", "pages/10.jpg"), images.stream().map(item -> item.get("name")).toList());
        assertArrayEquals(expected, service.image(42L, 2, false).bytes());
        assertEquals("image/png", service.image(42L, 2, false).mimeType());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.image(42L, 4, false)).getStatusCode().value());
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.image(42L, 1, false)).getStatusCode().value());
        verifyNoInteractions(komga);
    }

    @Test
    void thumbnailsAreBoundedToThreeHundredPixelsAndCached() throws Exception {
        Path archive = zip(Map.of("1.png", png(1200, 600)));
        when(mounted.findReadableArchive("Example", gallery.getFilename(), 42L)).thenReturn(Optional.of(archive));
        GalleryMediaService.ImageData thumbnail = service.image(42L, 1, true);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
        assertEquals("image/jpeg", thumbnail.mimeType());
        assertEquals(300, decoded.getWidth());
        assertEquals(150, decoded.getHeight());
        assertSame(thumbnail, service.image(42L, 1, true));
        verify(mounted, times(1)).findReadableArchive("Example", gallery.getFilename(), 42L);
        decoded.flush();
    }

    @Test
    void localArchiveWinsForPagesImagesAndCoverEvenWhenKomgaIsAvailable() throws Exception {
        gallery.setKomgaBookId("book-id");
        gallery.setToken("deletedgallerytoken");
        gallery.setGalleryUrl("https://e-hentai.org/g/42/deletedgallerytoken/");
        byte[] first = png(120, 60);
        byte[] second = png(40, 80);
        Path archive = zip(Map.of("1.png", first, "2.png", second));
        when(mounted.findReadableArchive("Example", gallery.getFilename(), 42L)).thenReturn(Optional.of(archive));
        when(komga.configured()).thenReturn(true);

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("ARCHIVE", page.get("source"));
        assertEquals(2, page.get("total"));
        assertArrayEquals(second, service.image(42L, 2, false).bytes());
        assertArrayEquals(service.image(42L, 1, true).bytes(), service.cover(42L).bytes());
        verify(mounted, times(1)).findReadableArchive("Example", gallery.getFilename(), 42L);
        verifyNoInteractions(komga);
    }

    @Test
    void actualMountedArchiveDiscoveryFindsRenamedLocalFileByGidWithAnUnavailableSourceGallery() throws Exception {
        Path library = directory.resolve("library");
        Path series = library.resolve("Example");
        Files.createDirectories(series);
        byte[] expected = png(240, 120);
        Path renamedArchive = series.resolve("[42] renamed title.cbz");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(renamedArchive))) {
            output.putNextEntry(new ZipEntry("001.png"));
            output.write(expected);
            output.closeEntry();
        }
        config.getArchiveStorage().setMountPath(library.toString());
        gallery.setToken("deletedgallerytoken");
        gallery.setGalleryUrl("https://e-hentai.org/g/42/deletedgallerytoken/");
        gallery.setKomgaBookId("book-id");
        service = new GalleryMediaService(mapper, komga, new MountedArchiveStorage(config), config);

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("ARCHIVE", page.get("source"));
        assertEquals(1, page.get("total"));
        assertArrayEquals(expected, service.image(42L, 1, false).bytes());
        BufferedImage cover = ImageIO.read(new ByteArrayInputStream(service.cover(42L).bytes()));
        assertNotNull(cover);
        assertEquals(240, cover.getWidth());
        assertEquals(120, cover.getHeight());
        cover.flush();
        verifyNoInteractions(komga);
    }

    @Test
    void corruptLocalArchiveFallsBackToTheAssociatedKomgaLibrary() throws Exception {
        gallery.setKomgaBookId("book-id");
        Path corruptArchive = directory.resolve("broken.cbz");
        Files.writeString(corruptArchive, "not a zip archive", StandardCharsets.UTF_8);
        when(mounted.findReadableArchive("Example", gallery.getFilename(), 42L)).thenReturn(Optional.of(corruptArchive));
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("book-id")).thenReturn(2);
        byte[] image = png(100, 100);
        when(komga.image("book-id", 2, false)).thenReturn(image);

        assertEquals("KOMGA", service.page(42L, 1, 10).get("source"));
        assertArrayEquals(image, service.image(42L, 2, false).bytes());
        verify(komga, times(1)).pageCount("book-id");
    }

    @Test
    void failedKomgaAndMissingLocalArchiveReturnUnavailableInsteadOfUsingTheStoredSourceUrl() throws Exception {
        gallery.setKomgaBookId("missing-book");
        gallery.setToken("deletedgallerytoken");
        gallery.setGalleryUrl("https://exhentai.org/g/42/deletedgallerytoken/");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("missing-book")).thenThrow(new KomgaMediaClient.KomgaHttpException(404));
        when(komga.findBook(42L, gallery.getFilename())).thenReturn(Optional.empty());

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("NONE", page.get("source"));
        assertEquals(false, page.get("available"));
        assertEquals(List.of(), page.get("images"));
        assertDiagnostic(page, "KOMGA_STALE_BOOK");
        assertDiagnostic(page, "KOMGA_NOT_FOUND");
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.cover(42L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.image(42L, 1, false)).getStatusCode().value());
        verify(komga, times(1)).pageCount("missing-book");
        verify(komga, times(1)).findBook(42L, gallery.getFilename());
        verify(komga, never()).image(anyString(), anyInt(), anyBoolean());
        verify(komga, never()).cover(anyString());
    }

    @Test
    void komgaCoverFallsBackToFirstLocalLibraryPageWhenThumbnailEndpointsAreUnavailable() throws Exception {
        gallery.setKomgaBookId("book-id");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("book-id")).thenReturn(2);
        when(komga.cover("book-id")).thenThrow(new IOException("cover endpoint unavailable"));
        when(komga.image("book-id", 1, true)).thenThrow(new IOException("page thumbnail endpoint unavailable"));
        when(komga.image("book-id", 1, false)).thenReturn(png(900, 600));

        GalleryMediaService.ImageData cover = service.cover(42L);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(cover.bytes()));
        assertEquals(300, decoded.getWidth());
        assertEquals(200, decoded.getHeight());
        assertSame(cover, service.cover(42L));
        verify(komga, times(1)).cover("book-id");
        verify(komga, times(1)).image("book-id", 1, true);
        verify(komga, times(1)).image("book-id", 1, false);
        decoded.flush();
    }

    @Test
    void obsoleteKomgaBookIdIsRediscoveredAndAllPreviewRequestsUseTheReplacementId() throws Exception {
        gallery.setKomgaBookId("old-book-id");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("old-book-id")).thenThrow(new KomgaMediaClient.KomgaHttpException(404));
        when(komga.findBook(42L, gallery.getFilename()))
                .thenReturn(Optional.of(new KomgaMediaClient.PreviewBook("replacement-book-id", 11)));
        byte[] expected = png(120, 60);
        when(komga.image("replacement-book-id", 11, false)).thenReturn(expected);
        when(komga.cover("replacement-book-id")).thenReturn(expected);

        Map<String, Object> page = service.page(42L, 2, 10);
        assertEquals("KOMGA", page.get("source"));
        assertEquals(11, page.get("total"));
        assertEquals(true, page.get("available"));
        assertArrayEquals(expected, service.image(42L, 11, false).bytes());
        BufferedImage cover = ImageIO.read(new ByteArrayInputStream(service.cover(42L).bytes()));
        assertNotNull(cover);
        cover.flush();
        verify(komga, times(1)).findBook(42L, gallery.getFilename());
        verify(komga, times(1)).image("replacement-book-id", 11, false);
        verify(komga, times(1)).cover("replacement-book-id");
        verify(komga, never()).image(eq("old-book-id"), anyInt(), anyBoolean());
        verify(komga, never()).cover("old-book-id");
    }

    @Test
    void importedGalleryWithoutStoredBookIdCanDiscoverItsExistingLocalKomgaBook() throws Exception {
        gallery.setDownloadStatus("IMPORTED");
        when(komga.configured()).thenReturn(true);
        when(komga.findBook(42L, gallery.getFilename()))
                .thenReturn(Optional.of(new KomgaMediaClient.PreviewBook("discovered-book-id", 3)));

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("KOMGA", page.get("source"));
        assertEquals(3, page.get("total"));
        assertEquals(List.of(), page.get("diagnostics"));
        verify(komga).findBook(42L, gallery.getFilename());
        verify(komga, never()).pageCount(anyString());
    }

    @Test
    void zeroPageKomgaMappingCanBeRediscoveredAfterAScan() throws Exception {
        gallery.setKomgaBookId("empty-book-id");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("empty-book-id")).thenReturn(0);
        when(komga.findBook(42L, gallery.getFilename()))
                .thenReturn(Optional.of(new KomgaMediaClient.PreviewBook("rescanned-book-id", 4)));

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("KOMGA", page.get("source"));
        assertEquals(4, page.get("total"));
        verify(komga).findBook(42L, gallery.getFilename());
    }

    @Test
    void invalidKomgaCredentialsAreReportedWithoutRunningBookDiscovery() throws Exception {
        gallery.setKomgaBookId("book-id");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("book-id")).thenThrow(new KomgaMediaClient.KomgaHttpException(403));

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("NONE", page.get("source"));
        assertEquals(false, page.get("available"));
        assertDiagnostic(page, "KOMGA_ACCESS_FAILED");
        assertTrue(String.valueOf(page.get("diagnostics")).contains("API Key"));
        verify(komga, never()).findBook(anyLong(), any());
    }

    @Test
    void komgaConnectionFailureIsReportedWithoutRunningBookDiscovery() throws Exception {
        gallery.setKomgaBookId("book-id");
        when(komga.configured()).thenReturn(true);
        when(komga.pageCount("book-id")).thenThrow(new IOException("连接超时"));

        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("NONE", page.get("source"));
        assertEquals(false, page.get("available"));
        assertDiagnostic(page, "KOMGA_UNAVAILABLE");
        verify(komga, never()).findBook(anyLong(), any());
    }

    @Test
    void explicitRefreshInvalidatesNegativeDiscoveryAndMetadataCachesAfterALocalArchiveAppears() throws Exception {
        Path library = directory.resolve("library");
        Path series = library.resolve("Example");
        Files.createDirectories(series);
        config.getArchiveStorage().setMountPath(library.toString());
        service = new GalleryMediaService(mapper, komga, new MountedArchiveStorage(config), config);
        assertEquals("NONE", service.page(42L, 1, 10).get("source"));

        byte[] expected = png(120, 60);
        Path archive = series.resolve("[42] now available.cbz");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("001.png"));
            output.write(expected);
            output.closeEntry();
        }
        assertEquals("NONE", service.page(42L, 1, 10).get("source"), "normal paging should reuse the negative cache");
        Map<String, Object> refreshed = service.page(42L, 1, 10, true);
        assertEquals("ARCHIVE", refreshed.get("source"));
        assertEquals(1, refreshed.get("total"));
        assertArrayEquals(expected, service.image(42L, 1, false).bytes());
        verify(mapper, times(2)).getPreviewMetadata(42L);
        verify(komga, times(1)).configured();
        verifyNoMoreInteractions(komga);
    }

    @Test
    void noSourceReturnsAnExplicitUnavailableStateAndRejectsBadRequests() throws Exception {
        gallery.setToken("deletedgallerytoken");
        gallery.setGalleryUrl("https://e-hentai.org/g/42/deletedgallerytoken/");
        when(mounted.findReadableArchive(any(), any(), any())).thenReturn(Optional.empty());
        Map<String, Object> page = service.page(42L, 1, 10);
        assertEquals("NONE", page.get("source"));
        assertEquals(false, page.get("available"));
        assertEquals(0, page.get("total"));
        assertEquals(List.of(), page.get("images"));
        assertFalse(String.valueOf(page.get("message")).isBlank());
        assertDiagnostic(page, "ARCHIVE_NOT_FOUND");
        assertDiagnostic(page, "KOMGA_NOT_CONFIGURED");
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.page(42L, 1, 30)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.image(42L, 0, true)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.page(43L, 1, 10)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.cover(42L)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.image(42L, 1, true)).getStatusCode().value());
        verify(komga, never()).pageCount(anyString());
        verify(komga, never()).image(anyString(), anyInt(), anyBoolean());
    }

    @Test
    void browserKomgaLinkUsesPublicContextPathWithoutApiCredentials() {
        gallery.setKomgaBookId("book id");
        config.getKomga().setUrl("http://internal-komga:25600/");
        config.getKomga().setPublicUrl("https://reader.example/komga/");
        config.getKomga().setApiKey("server-secret");
        Map<String, Object> link = service.komgaLink(42L);
        assertEquals(true, link.get("available"));
        assertEquals("https://reader.example/komga/book/book%20id", link.get("url"));
        assertFalse(String.valueOf(link).contains("server-secret"));
        config.getKomga().setPublicUrl("https://reader.example/komga/?apiKey=do-not-echo#secret");
        Map<String, Object> sanitized = service.komgaLink(42L);
        assertEquals("https://reader.example/komga/book/book%20id", sanitized.get("url"));
        assertFalse(String.valueOf(sanitized).contains("do-not-echo"));
        config.getKomga().setPublicUrl("https://user:password@reader.example/");
        assertEquals(false, service.komgaLink(42L).get("available"));
        gallery.setKomgaBookId(null);
        assertEquals(false, service.komgaLink(42L).get("available"));
        verifyNoInteractions(komga, mounted);
    }

    @SuppressWarnings("unchecked")
    private static void assertDiagnostic(Map<String, Object> page, String code) {
        Object value = page.get("diagnostics");
        assertInstanceOf(List.class, value);
        List<Map<String, String>> diagnostics = (List<Map<String, String>>) value;
        assertTrue(diagnostics.stream().anyMatch(item -> code.equals(item.get("code"))
                && item.get("message") != null && !item.get("message").isBlank()),
                () -> "Expected diagnostic " + code + ": " + diagnostics);
    }

    private Path zip(Map<String, byte[]> entries) throws IOException {
        Path archive = directory.resolve("fixture-" + System.nanoTime() + ".cbz");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return archive;
    }
    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes); image.flush();
        return bytes.toByteArray();
    }
}
