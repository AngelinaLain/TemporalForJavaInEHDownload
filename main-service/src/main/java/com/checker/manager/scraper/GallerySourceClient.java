package com.checker.manager.scraper;

import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceResult;
import com.checker.shared.Constants;
import com.checker.temporal.workflow.GallerySourceWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 调用抓取服务获取少量源站数据，主服务仍负责数据库和归档操作。 */
@Component
public class GallerySourceClient {
    private static final WorkflowOptions OPTIONS = WorkflowOptions.newBuilder()
            .setTaskQueue(Constants.SCRAPER_TASK_QUEUE)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
            .build();

    private final WorkflowClient workflows;

    public GallerySourceClient(WorkflowClient workflows) {
        this.workflows = workflows;
    }

    public List<String> fetchTags(long gid, String token) {
        return collect(new GallerySourceRequest(gid, token, null, GallerySourceRequest.Kind.TAGS)).tags();
    }

    public GalleryPageFingerprint fetchCover(long gid, String token, String galleryUrl) {
        return collect(new GallerySourceRequest(gid, token, galleryUrl, GallerySourceRequest.Kind.COVER)).cover();
    }

    private GallerySourceResult collect(GallerySourceRequest request) {
        try {
            return workflows.newWorkflowStub(GallerySourceWorkflow.class, OPTIONS).collect(request);
        } catch (WorkflowFailedException failure) {
            // 保留源站限额、登录失效等失败类型，供现有入库 Activity 的重试策略识别。
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof ApplicationFailure applicationFailure) throw applicationFailure;
            }
            throw new IllegalStateException("抓取服务未在时限内返回画廊数据", failure);
        }
    }
}
