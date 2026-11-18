package com.checker.service.workflow.rss;

import com.checker.dao.rss.RssStore;
import com.checker.model.dto.rss.RssSubscriptionRequest;
import com.checker.service.activity.rss.RssActivities;
import com.checker.shared.Constants;
import com.checker.dto.RssSearchItem;
import com.checker.dto.RssSearchPage;
import com.checker.temporalServices.activities.RssScraperActivity;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RssPollWorkflowTest {
    RssStore.Snapshot snapshot(String cursor,Long started) {
        var request=new RssSubscriptionRequest();request.setName("test");request.setKeyword("test");request.setMaxBackfillPages(2);request.validate();
        return new RssStore.Snapshot("sub",1,request,1000L,null,cursor,started,1000L,0,null,null);
    }
    RssSearchItem item(long gid,long time) {return new RssSearchItem(gid,"abc","https://e-hentai.org/g/"+gid+"/abc/","test",time);}
    void run(RssActivities activities,RssScraperActivity scraper) {
        try(var env=TestWorkflowEnvironment.newInstance()) {
            var main=env.newWorker(Constants.TASK_QUEUE);main.registerWorkflowImplementationTypes(RssPollWorkflowImpl.class);main.registerActivitiesImplementations(activities);
            env.newWorker(Constants.SCRAPER_TASK_QUEUE).registerActivitiesImplementations(scraper);env.start();
            var workflow=env.getWorkflowClient().newWorkflowStub(RssPollWorkflow.class,WorkflowOptions.newBuilder().setTaskQueue(Constants.TASK_QUEUE).setWorkflowId(UUID.randomUUID().toString()).build());
            workflow.poll("sub",1);
        }
    }
    @Test void pageBudgetLeavesDurableContinuationWithoutCompletingCoverage() {
        var activities=mock(RssActivities.class);var scraper=mock(RssScraperActivity.class);
        var s=snapshot(null,5000L);
        when(activities.rssLoad("sub",1)).thenReturn(s);when(activities.rssFetch(any())).thenReturn(List.of());
        when(scraper.scrapeRssSearchPage(any(),isNull())).thenReturn(new RssSearchPage(List.of(item(3,4000)),"https://e-hentai.org/?next=3",false));
        when(scraper.scrapeRssSearchPage(any(),eq("https://e-hentai.org/?next=3"))).thenReturn(new RssSearchPage(List.of(item(2,3000)),"https://e-hentai.org/?next=2",false));
        run(activities,scraper);
        verify(activities).rssCheckpoint(eq(s),eq("https://e-hentai.org/?next=2"),eq(false),eq(2));
        verify(activities,never()).rssCheckpoint(any(),any(),eq(true),anyInt());
        verify(activities).rssFinish(anyString(),eq(s),anyInt(),eq(2),isNull());
    }
    @Test void resumedScanStopsAfterVerifiedOldPageAndIgnoresRssAvailability() {
        var activities=mock(RssActivities.class);var scraper=mock(RssScraperActivity.class);
        var s=snapshot("https://e-hentai.org/?next=2",5000L);
        when(activities.rssLoad("sub",1)).thenReturn(s);
        when(activities.rssFetch(any())).thenThrow(new IllegalStateException("RSSHub offline"));
        when(scraper.scrapeRssSearchPage(any(),eq(s.cursor()))).thenReturn(new RssSearchPage(List.of(item(1,500)),"https://e-hentai.org/?next=1",false));
        run(activities,scraper);
        verify(activities).rssCheckpoint(eq(s),anyString(),eq(true),eq(1));
        ArgumentCaptor<String> error=ArgumentCaptor.forClass(String.class);
        verify(activities).rssFinish(anyString(),eq(s),anyInt(),eq(1),error.capture());
        assertTrue(error.getValue().contains("RSSHub offline"));
    }
    @Test void revisedSubscriptionStopsOldScanBeforeFetchingAnotherPage() {
        var activities=mock(RssActivities.class);var scraper=mock(RssScraperActivity.class);
        var s=snapshot(null,5000L);
        when(activities.rssLoad("sub",1)).thenReturn(s,null);
        when(activities.rssFetch(any())).thenReturn(List.of());
        run(activities,scraper);verifyNoInteractions(scraper);
    }
}
