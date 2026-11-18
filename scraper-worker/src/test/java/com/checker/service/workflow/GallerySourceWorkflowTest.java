package com.checker.service.workflow;

import com.checker.dto.GalleryPageFingerprint;
import com.checker.dto.GallerySourceRequest;
import com.checker.dto.GallerySourceRequest.Kind;
import com.checker.dto.GallerySourceResult;
import com.checker.shared.Constants;
import com.checker.temporal.activity.GallerySourceActivity;
import com.checker.temporal.workflow.GallerySourceWorkflow;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GallerySourceWorkflowTest {
    @Test
    void returnsTagsAndCoverThroughTheScraperQueue() {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker(Constants.SCRAPER_TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(GallerySourceWorkflowImpl.class);
            worker.registerActivitiesImplementations(new FixtureSource());
            environment.start();

            var tags = stub(environment).collect(new GallerySourceRequest(42, "abc123", null, Kind.TAGS));
            assertEquals(List.of("fixture:tag"), tags.tags());
            assertNull(tags.cover());

            var cover = stub(environment).collect(new GallerySourceRequest(42, "abc123",
                    "https://e-hentai.org/g/42/abc123/", Kind.COVER));
            assertNull(cover.tags());
            assertEquals(42L, cover.cover().getGid());
            assertEquals("EH_COVER", cover.cover().getSource());
        }
    }

    private static GallerySourceWorkflow stub(TestWorkflowEnvironment environment) {
        return environment.getWorkflowClient().newWorkflowStub(GallerySourceWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(Constants.SCRAPER_TASK_QUEUE).setWorkflowExecutionTimeout(Duration.ofMinutes(2)).build());
    }

    public static class FixtureSource implements GallerySourceActivity {
        @Override
        public GallerySourceResult collectGallerySource(GallerySourceRequest request) {
            return switch (request.kind()) {
                case TAGS -> new GallerySourceResult(List.of("fixture:tag"), null);
                case COVER -> new GallerySourceResult(null, GalleryPageFingerprint.builder()
                        .gid(request.gid()).source("EH_COVER").build());
            };
        }
    }
}
