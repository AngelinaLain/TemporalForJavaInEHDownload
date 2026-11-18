package com.checker.manager.source;

import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceRequest.Kind;
import com.checker.infrastructure.network.EhNetworkClient;
import com.checker.shared.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GallerySourceManagerTest {
    private static final String GALLERY_URL = "https://e-hentai.org/g/42/abc123/";
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private final EhNetworkClient network = mock(EhNetworkClient.class);
    private final GallerySourceManager activity = new GallerySourceManager(network);

    @Test
    void extractsEhCoverFromMetadataCssAndThumbnailFallbacks() {
        var document = Jsoup.parse("""
                <meta property='og:image' content='/cover.jpg'>
                <div id='gd1'><div style="background: url('//img.example/front.jpg')"></div></div>
                <div id='gdt'><img data-src='' src='https://img.example/page1.jpg'></div>
                """, "https://e-hentai.org/g/1/token/");

        assertEquals(List.of("https://e-hentai.org/cover.jpg", "https://img.example/front.jpg",
                        "https://img.example/page1.jpg"),
                GallerySourceManager.extractSourceCoverUrls(document));
    }

    @Test
    void coverCandidatesPreservePriorityAndRemoveDuplicatesAndInlineImages() {
        var document = Jsoup.parse("""
                <meta property='og:image' content='https://img.example/front.jpg'>
                <meta name='twitter:image' content='https://img.example/front.jpg'>
                <div id='gdt'><img data-src='data:image/png;base64,invalid' src='/page.jpg'></div>
                <div class='gdtl'><img data-src='/page.jpg' src='javascript:alert(1)'></div>
                """, GALLERY_URL);

        assertEquals(List.of("https://img.example/front.jpg", "https://e-hentai.org/page.jpg"),
                GallerySourceManager.extractSourceCoverUrls(document));
        assertEquals(List.of(), GallerySourceManager.extractSourceCoverUrls(null));
    }

    @Test
    void fetchesTagsUsingFixedGdataEndpointAndRequestedIdentity() throws Exception {
        when(network.postJson(eq(Constants.EHENTAI_API_URL), anyString())).thenReturn("""
                {"gmetadata":[{"gid":42,"tags":["language:chinese","artist:someone"]}]}
                """);

        var result = activity.collectGallerySource(new GallerySourceRequest(42, "abc123", null, Kind.TAGS));

        assertEquals(List.of("language:chinese", "artist:someone"), result.tags());
        assertNull(result.cover());
        var payload = ArgumentCaptor.forClass(String.class);
        verify(network).postJson(eq(Constants.EHENTAI_API_URL), payload.capture());
        var request = new ObjectMapper().readTree(payload.getValue());
        assertEquals("gdata", request.path("method").asText());
        assertEquals(1, request.path("namespace").asInt());
        assertEquals(1, request.path("gidlist").size());
        assertEquals(42, request.path("gidlist").get(0).get(0).asLong());
        assertEquals("abc123", request.path("gidlist").get(0).get(1).asText());
        verifyNoMoreInteractions(network);
    }

    @Test
    void missingTagsRemainUnknownWhileExplicitEmptyTagsAreValid() throws Exception {
        for (String response : List.of("{}", "{\"gmetadata\":[]}",
                "{\"gmetadata\":[{\"gid\":42}]}",
                "{\"gmetadata\":[{\"gid\":42,\"tags\":null}]}")) {
            when(network.postJson(eq(Constants.EHENTAI_API_URL), anyString())).thenReturn(response);
            var result = activity.collectGallerySource(new GallerySourceRequest(42, "abc123", null, Kind.TAGS));
            assertNull(result.tags(), response);
            assertNull(result.cover());
        }
        when(network.postJson(eq(Constants.EHENTAI_API_URL), anyString()))
                .thenReturn("{\"gmetadata\":[{\"gid\":42,\"tags\":[]}]}");
        assertEquals(List.of(), activity.collectGallerySource(
                new GallerySourceRequest(42, "abc123", null, Kind.TAGS)).tags());
    }

    @Test
    void mismatchedIdentityAndMalformedTagsCannotBecomeSuccessfulMetadata() {
        for (String response : List.of("{\"gmetadata\":[{\"gid\":43,\"tags\":[\"wrong\"]}]}",
                "{\"gmetadata\":[{\"gid\":42,\"tags\":[123]}]}",
                "{\"gmetadata\":[{\"gid\":42,\"tags\":\"wrong\"}]}")) {
            when(network.postJson(eq(Constants.EHENTAI_API_URL), anyString())).thenReturn(response);
            assertThrows(IOException.class, () -> activity.collectGallerySource(
                    new GallerySourceRequest(42, "abc123", null, Kind.TAGS)), response);
        }
    }

    @Test
    void createsRealCoverFingerprintAfterAnUndecodableCandidate() throws Exception {
        when(network.getHtml(GALLERY_URL)).thenReturn("""
                <meta property='og:image' content='https://img.example/broken.jpg'>
                <div id='gdt'><img src='https://img.example/valid.png'></div>
                """);
        when(network.getBytes("https://img.example/broken.jpg", MAX_IMAGE_BYTES)).thenReturn(new byte[]{1, 2, 3});
        when(network.getBytes("https://img.example/valid.png", MAX_IMAGE_BYTES)).thenReturn(image());

        var result = activity.collectGallerySource(new GallerySourceRequest(42, "abc123", null, Kind.COVER));

        assertNull(result.tags());
        assertNotNull(result.cover());
        assertEquals(42L, result.cover().getGid());
        assertEquals(1, result.cover().getPageIndex());
        assertEquals("source-cover", result.cover().getPageName());
        assertEquals("EH_COVER", result.cover().getSource());
        assertEquals(64, result.cover().getWidth());
        assertEquals(96, result.cover().getHeight());
        assertEquals(16, result.cover().getPerceptualHash().length());
        assertEquals(16, result.cover().getCenterHash().length());
        verify(network).getHtml(GALLERY_URL);
    }

    @Test
    void acceptsExGalleryIdentityAndUpgradesHttpBeforeFetching() throws Exception {
        when(network.getHtml("https://exhentai.org/g/42/abc123/"))
                .thenReturn("<meta property='og:image' content='https://img.example/valid.png'>");
        when(network.getBytes("https://img.example/valid.png", MAX_IMAGE_BYTES)).thenReturn(image());

        var result = activity.collectGallerySource(new GallerySourceRequest(
                42, null, "http://exhentai.org/g/42/abc123/", Kind.COVER));

        assertNotNull(result.cover());
        verify(network).getHtml("https://exhentai.org/g/42/abc123/");
    }

    @Test
    void refusesArbitraryPagesAndConflictingIdentityBeforeNetworkAccess() {
        List<String> invalidUrls = List.of(
                "https://127.0.0.1/g/42/abc123/",
                "https://e-hentai.org.evil.example/g/42/abc123/",
                "https://user@e-hentai.org/g/42/abc123/",
                "https://e-hentai.org:444/g/42/abc123/",
                "https://e-hentai.org/g/43/abc123/",
                "https://e-hentai.org/g/42/other/",
                "https://e-hentai.org/?f_search=test",
                GALLERY_URL + "?p=1",
                GALLERY_URL + "#fragment",
                "file:///g/42/abc123/"
        );
        for (String url : invalidUrls) {
            assertThrows(IllegalArgumentException.class, () -> activity.collectGallerySource(
                    new GallerySourceRequest(42, "abc123", url, Kind.COVER)), url);
        }
        for (GallerySourceRequest request : List.of(
                new GallerySourceRequest(0, "abc123", null, Kind.TAGS),
                new GallerySourceRequest(42, null, null, Kind.COVER),
                new GallerySourceRequest(42, "bad/token", null, Kind.TAGS),
                new GallerySourceRequest(42, "abc123", null, null))) {
            assertThrows(IllegalArgumentException.class, () -> activity.collectGallerySource(request));
        }
        verifyNoInteractions(network);
    }

    @Test
    void limitsCoverFallbacksToSixAndFailsInsteadOfReturningAnEmptySuccess() {
        StringBuilder html = new StringBuilder("<div id='gdt'>");
        for (int i = 0; i < 7; i++) html.append("<img src='https://img.example/").append(i).append(".png'>");
        html.append("</div>");
        when(network.getHtml(GALLERY_URL)).thenReturn(html.toString());
        when(network.getBytes(anyString(), eq(MAX_IMAGE_BYTES))).thenReturn(new byte[]{1, 2, 3});

        assertThrows(IOException.class, () -> activity.collectGallerySource(
                new GallerySourceRequest(42, "abc123", null, Kind.COVER)));

        verify(network, times(6)).getBytes(anyString(), eq(MAX_IMAGE_BYTES));
        verify(network, never()).getBytes(eq("https://img.example/6.png"), anyLong());
    }

    private static byte[] image() throws IOException {
        BufferedImage image = new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, ((x * 4) << 16) | ((y * 2) << 8) | ((x + y) & 255));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
