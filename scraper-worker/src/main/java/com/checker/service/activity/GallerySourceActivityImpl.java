package com.checker.service.activity;

import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceResult;
import com.checker.manager.source.GallerySourceManager;
import com.checker.shared.Constants;
import com.checker.temporal.activity.GallerySourceActivity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 接收源站读取任务，保持跨服务活动契约独立于外部请求实现。 */
@Component
@ActivityImpl(taskQueues = Constants.SCRAPER_TASK_QUEUE)
public class GallerySourceActivityImpl implements GallerySourceActivity {
    private final GallerySourceManager source;

    public GallerySourceActivityImpl(GallerySourceManager source) {
        this.source = source;
    }

    @Override
    public GallerySourceResult collectGallerySource(GallerySourceRequest request) throws IOException {
        return source.collectGallerySource(request);
    }
}
