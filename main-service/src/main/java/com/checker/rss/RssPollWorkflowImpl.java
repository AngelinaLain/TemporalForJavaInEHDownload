package com.checker.rss;

import com.checker.common.Constants;
import com.checker.dto.RssSearchPage;
import com.checker.temporalServices.activities.RssScraperActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

@WorkflowImpl(taskQueues=Constants.TASK_QUEUE)
public class RssPollWorkflowImpl implements RssPollWorkflow {
    private final RssActivities activities=Workflow.newActivityStub(RssActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2)).setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build()).build());
    private final RssScraperActivity scraper=Workflow.newActivityStub(RssScraperActivity.class, ActivityOptions.newBuilder()
            .setTaskQueue(Constants.SCRAPER_TASK_QUEUE).setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).setInitialInterval(Duration.ofSeconds(30)).build()).build());

    @Override public void poll(String id,int revision) {
        RssStore.Snapshot initial=activities.rssLoad(id,revision);
        if (initial==null) return;
        String run=Workflow.getInfo().getRunId();
        activities.rssBeginRun(run,initial);
        int count=0,pages=0;
        String error=null;
        try {
            boolean windowChanged=false;
            try {
                var feed=activities.rssFetch(initial.request().getSearchOptions());
                int added=activities.rssRecord(initial,feed);
                count+=added;
                windowChanged=!feed.isEmpty() && added==feed.size();
            }
            catch (ActivityFailure e) { error=message(e); }
            RssStore.Snapshot current=activities.rssLoad(id,revision);
            if (current==null) return;
            long now=Workflow.currentTimeMillis();
            // An empty/unchanged RSS response does not prove coverage. Failures also trigger direct recovery.
            if (current.scanStarted()!=null || current.lastBackfill()==null || error!=null || windowChanged
                    || current.lastSuccess()==null || now-current.lastSuccess()>current.request().getIntervalMinutes()*120000L
                    || now-current.lastBackfill() >= current.request().getBackfillIntervalHours()*3600000L) {
                if (current.scanStarted()==null) current=activities.rssBeginScan(current);
                if (current==null) return;
                String cursor=current.cursor();
                for (int i=0;i<current.request().getMaxBackfillPages();i++) {
                    if (activities.rssLoad(id,revision)==null) return;
                    RssSearchPage page=scraper.scrapeRssSearchPage(current.request().getSearchOptions(),cursor);
                    count+=activities.rssRecord(current,page.items());
                    pages++;
                    boolean complete=page.endOfResults() || olderThanBoundary(page,current.scanBefore());
                    activities.rssCheckpoint(current,page.nextUrl(),complete,current.scanPages()+pages);
                    if (complete) break;
                    if (page.nextUrl()==null || page.nextUrl().equals(cursor)) throw new IllegalStateException("补查没有向下一页推进");
                    cursor=page.nextUrl();
                    Workflow.sleep(Duration.ofSeconds(5));
                }
            }
        } catch (Exception e) { error=message(e); }
        finally { activities.rssFinish(run,initial,count,pages,error); }
    }
    static boolean olderThanBoundary(RssSearchPage page,long boundary) {
        if (page.items().isEmpty()) return false;
        long previous=Long.MAX_VALUE;
        for (var item:page.items()) {
            if (item.publishedAt()==null || item.publishedAt()>previous) return false;
            previous=item.publishedAt();
            if (item.publishedAt()>=boundary) return false;
        }
        return true;
    }
    private static String message(Throwable error) {
        Throwable cause=error;
        while (cause.getCause()!=null) cause=cause.getCause();
        return cause.getMessage()==null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
