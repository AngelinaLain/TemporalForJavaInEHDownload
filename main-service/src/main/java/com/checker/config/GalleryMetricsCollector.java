package com.checker.config;

import com.checker.common.DownloadStatus;
import com.checker.mapper.EhGalleriesMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes GalleryImport domain metrics alongside Spring Boot's standard
 * Micrometer metrics. Values are refreshed independently of Prometheus scrapes
 * so a temporary database failure never makes the actuator endpoint fail.
 */
@Component
public class GalleryMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(GalleryMetricsCollector.class);

    private final EhGalleriesMapper galleriesMapper;
    private final MultiGauge statusGauge;
    private final AtomicLong activeDownloads = new AtomicLong();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();

    public GalleryMetricsCollector(EhGalleriesMapper galleriesMapper, MeterRegistry registry) {
        this.galleriesMapper = galleriesMapper;
        this.statusGauge = MultiGauge.builder("galleryimport.download.status")
                .description("Number of galleries in each download state")
                .register(registry);

        Gauge.builder("galleryimport.download.active", activeDownloads, AtomicLong::doubleValue)
                .description("Number of downloads currently in progress")
                .register(registry);
        Gauge.builder("galleryimport.downloaded", downloadedBytes, AtomicLong::doubleValue)
                .description("Bytes downloaded for all active downloads")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder("galleryimport.download.expected", totalBytes, AtomicLong::doubleValue)
                .description("Expected total bytes for all active downloads")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder("galleryimport.download.progress.ratio", this, GalleryMetricsCollector::progressRatio)
                .description("Aggregate progress of active downloads from 0 to 1")
                .register(registry);
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${management.gallery-metrics.refresh-interval-ms:15000}")
    public void refresh() {
        try {
            refreshStatusCounts();
            refreshDownloadProgress();
        } catch (Exception e) {
            log.warn("Unable to refresh GalleryImport Prometheus metrics: {}", e.getMessage());
        }
    }

    private void refreshStatusCounts() {
        Map<DownloadStatus, Long> counts = new EnumMap<>(DownloadStatus.class);
        for (DownloadStatus status : DownloadStatus.values()) {
            counts.put(status, 0L);
        }

        for (Map<String, Object> row : galleriesMapper.countByDownloadStatus()) {
            try {
                DownloadStatus status = DownloadStatus.valueOf(String.valueOf(row.get("status")).toUpperCase(Locale.ROOT));
                counts.put(status, number(row.get("cnt")));
            } catch (IllegalArgumentException ignored) {
                // Unknown legacy values are intentionally excluded from the bounded status label set.
            }
        }

        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        counts.forEach((status, count) ->
                rows.add(MultiGauge.Row.of(Tags.of("status", status.name()), count)));
        statusGauge.register(rows, true);
    }

    private void refreshDownloadProgress() {
        Map<String, Object> progress = galleriesMapper.getDownloadProgressMetrics();
        activeDownloads.set(number(progress.get("active")));
        downloadedBytes.set(number(progress.get("downloaded_bytes")));
        totalBytes.set(number(progress.get("total_bytes")));
    }

    private double progressRatio() {
        long expected = totalBytes.get();
        if (expected <= 0) {
            return 0.0;
        }
        return Math.min(1.0, downloadedBytes.get() / (double) expected);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
