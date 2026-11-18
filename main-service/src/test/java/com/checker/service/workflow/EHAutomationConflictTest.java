package com.checker.service.workflow;

import com.checker.model.dto.workflow.WorkflowSettings;
import com.checker.service.activity.workflow.DatabaseActivity;
import com.checker.shared.Constants;
import com.checker.common.DownloadStatus;
import com.checker.dto.SearchOptions;
import com.checker.entity.EhGalleriesEntity;
import com.checker.service.activity.notification.NotificationActivity;
import com.checker.temporalServices.activities.ScraperActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EHAutomationConflictTest {
    @Test void anAlreadyRunningChildDoesNotStopRemainingBatchItems() {
        var database=mock(DatabaseActivity.class,withSettings().withoutAnnotations());
        var scraper=mock(ScraperActivity.class,withSettings().withoutAnnotations());
        var notification=mock(NotificationActivity.class,withSettings().withoutAnnotations());
        var settings=new WorkflowSettings();settings.setMaxConcurrency(1);
        when(database.loadWorkflowSettings()).thenReturn(settings);
        var galleries=List.of(gallery(1),gallery(2),gallery(3));
        when(database.getGalleriesByIds(anyList())).thenReturn(galleries);
        when(scraper.scrapeGalleriesByUrls(anyList())).thenReturn(galleries);
        var completed=ConcurrentHashMap.<Long>newKeySet();
        try(var env=TestWorkflowEnvironment.newInstance()) {
            var worker=env.newWorker(Constants.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(EHAutomationWorkflowImpl.class);
            worker.registerActivitiesImplementations(database,notification);
            worker.registerWorkflowImplementationFactory(SingleGalleryDownloadWorkflow.class,()->(gallery,compensate,config)->{
                if (gallery.getGid()==1L) Workflow.sleep(Duration.ofDays(10));
                else completed.add(gallery.getGid());
            });
            env.newWorker(Constants.SCRAPER_TASK_QUEUE).registerActivitiesImplementations(scraper);
            env.start();
            var existing=env.getWorkflowClient().newWorkflowStub(SingleGalleryDownloadWorkflow.class,WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE).setWorkflowId("single-gallery-1").build());
            WorkflowClient.start(existing::processSingleGallery,galleries.get(0),true,settings);
            var parent=env.getWorkflowClient().newWorkflowStub(EHAutomationWorkflow.class,WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE).setWorkflowId("test-rss-batch").build());
            parent.executeAutomation(SearchOptions.builder().galleryUrls(List.of("https://e-hentai.org/g/1/abc/")).build());
            assertEquals(java.util.Set.of(2L,3L),completed);
        }
    }
    private EhGalleriesEntity gallery(long gid) {
        var gallery=new EhGalleriesEntity();gallery.setGid(gid);gallery.setTitle("Title");gallery.setDownloadStatus(DownloadStatus.DOWNLOADED.getValue());return gallery;
    }
}
