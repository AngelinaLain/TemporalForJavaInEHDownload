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
 * 定时把画廊状态和下载进度汇总成监控指标，供 Prometheus 读取。
 * 采集与监控请求分开执行，数据库短暂不可用时继续保留上次结果，避免监控接口一起失败。
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

    /**
     * 服务启动后先采集一次，让监控无需等待第一个定时周期。
     */
    @PostConstruct
    public void initialize() {
        refresh();
    }

    /**
     * 定时刷新状态数量和下载进度；采集失败只记录错误，保留已有指标。
     */
    @Scheduled(fixedDelayString = "${management.gallery-metrics.refresh-interval-ms:15000}")
    public void refresh() {
        try {
            refreshStatusCounts();
            refreshDownloadProgress();
        } catch (Exception e) {
            log.warn("Unable to refresh GalleryImport Prometheus metrics: {}", e.getMessage());
        }
    }

    /**
     * 按固定的下载状态集合更新数量，避免历史异常状态产生无限增加的监控标签。
     */
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
                // 历史记录中的未知状态不加入监控标签，防止标签数量随异常数据增长。
            }
        }

        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        counts.forEach((status, count) ->
                rows.add(MultiGauge.Row.of(Tags.of("status", status.name()), count)));
        statusGauge.register(rows, true);
    }

    /**
     * 读取正在下载记录的汇总字节数和任务数，更新总体进度指标。
     */
    private void refreshDownloadProgress() {
        Map<String, Object> progress = galleriesMapper.getDownloadProgressMetrics();
        activeDownloads.set(number(progress.get("active")));
        downloadedBytes.set(number(progress.get("downloaded_bytes")));
        totalBytes.set(number(progress.get("total_bytes")));
    }

    /**
     * 计算所有进行中下载的总体完成比例，没有预计体积时返回零，并将结果限制在百分之百以内。
     */
    private double progressRatio() {
        long expected = totalBytes.get();
        if (expected <= 0) {
            return 0.0;
        }
        return Math.min(1.0, downloadedBytes.get() / (double) expected);
    }

    /**
     * 把数据库聚合值转成长整数，缺失或不是数字时按零处理。
     */
    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
