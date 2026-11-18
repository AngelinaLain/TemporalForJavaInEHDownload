package com.checker.service.activity.rss;
import com.checker.dao.rss.RssStore;
import com.checker.dto.RssSearchItem;
import com.checker.dto.SearchOptions;
import io.temporal.activity.ActivityInterface;
import java.util.List;
@ActivityInterface
public interface RssActivities {
    void rssTick();
    RssStore.Snapshot rssLoad(String id,int revision);
    void rssBeginRun(String run,RssStore.Snapshot s);
    List<RssSearchItem> rssFetch(SearchOptions options);
    int rssRecord(RssStore.Snapshot s,List<RssSearchItem> items);
    RssStore.Snapshot rssBeginScan(RssStore.Snapshot s);
    void rssCheckpoint(RssStore.Snapshot s,String cursor,boolean complete,int pages);
    void rssFinish(String run,RssStore.Snapshot s,int count,int pages,String error);
}
