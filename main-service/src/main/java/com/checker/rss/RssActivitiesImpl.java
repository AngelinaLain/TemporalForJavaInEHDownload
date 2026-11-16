package com.checker.rss;
import com.checker.common.Constants;
import com.checker.dto.RssSearchItem;
import com.checker.dto.SearchOptions;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
@ActivityImpl(taskQueues=Constants.TASK_QUEUE)
public class RssActivitiesImpl implements RssActivities {
    private final RssStore store;
    private final RssFeedClient feed;
    private final RssRuntime runtime;
    public RssActivitiesImpl(RssStore store,RssFeedClient feed,RssRuntime runtime) {this.store=store;this.feed=feed;this.runtime=runtime;}
    public void rssTick(){runtime.tick();}
    public RssStore.Snapshot rssLoad(String id,int revision){return store.load(id,revision);}
    public void rssBeginRun(String run,RssStore.Snapshot s){store.beginRun(run,s);}
    public List<RssSearchItem> rssFetch(SearchOptions options){return feed.fetch(options);}
    public int rssRecord(RssStore.Snapshot s,List<RssSearchItem> items){return store.record(s,items);}
    public RssStore.Snapshot rssBeginScan(RssStore.Snapshot s){return store.beginScan(s);}
    public void rssCheckpoint(RssStore.Snapshot s,String cursor,boolean complete,int pages){store.checkpoint(s,cursor,complete,pages);}
    public void rssFinish(String run,RssStore.Snapshot s,int count,int pages,String error){store.finish(run,s,count,pages,error);}
}
