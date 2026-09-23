package com.checker.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EhGalleriesPayloadCompatibilityTest {
    private final DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();

    @Test
    void cleanupJournalNeverLeaksToWorkersWithOlderGalleryEntities() throws Exception {
        EhGalleriesEntity gallery = new EhGalleriesEntity();
        gallery.setGid(884927L);
        gallery.setFilename("[884927] gallery.cbz");
        gallery.setStoragePath("Series");
        gallery.setSeriesSyncSignature("1:Series:0");
        gallery.setSeriesCleanupPath("");
        gallery.setSeriesCleanupFilename("old.cbz");
        JsonNode value = new ObjectMapper().readTree(converter.toPayload(gallery).orElseThrow()
                .getData().toStringUtf8());
        assertFalse(value.has("seriesCleanupPath"));
        assertFalse(value.has("seriesCleanupFilename"));
        assertEquals(884927L, value.path("gid").asLong());
        assertEquals("Series", value.path("storagePath").asText());
        assertEquals("1:Series:0", value.path("seriesSyncSignature").asText());
        // JSON exclusion must not remove the Java properties MyBatis persists.
        assertEquals("old.cbz", gallery.getSeriesCleanupFilename());
    }

    @Test
    void defaultNullJournalFieldsAreAlsoAbsentFromNewPayloads() throws Exception {
        JsonNode value = new ObjectMapper().readTree(converter.toPayload(new EhGalleriesEntity())
                .orElseThrow().getData().toStringUtf8());
        assertFalse(value.has("seriesCleanupPath"));
        assertFalse(value.has("seriesCleanupFilename"));
    }

    @Test
    void readsAlreadyRecordedActivityBatchesWithJournalAndFutureFields() throws Exception {
        Payload recorded = Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFromUtf8("""
                        [{"gid":884927,"title":"Historical gallery","storagePath":"Series",
                          "seriesCleanupPath":"old-series","seriesCleanupFilename":"old.cbz",
                          "futureMetadata":{"revision":2}}]
                        """)).build();
        ParameterizedType type = (ParameterizedType) getClass().getDeclaredField("galleryBatch").getGenericType();
        List<?> batch = converter.fromPayload(recorded, List.class, type);
        EhGalleriesEntity gallery = (EhGalleriesEntity) batch.get(0);
        assertEquals(884927L, gallery.getGid());
        assertEquals("Historical gallery", gallery.getTitle());
        assertEquals("Series", gallery.getStoragePath());
        assertNull(gallery.getSeriesCleanupPath());
        assertNull(gallery.getSeriesCleanupFilename());
    }

    private List<EhGalleriesEntity> galleryBatch;
}
