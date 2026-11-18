package com.checker.service.rss;

import com.checker.dao.rss.RssStore;
import com.checker.dto.RssSearchItem;
import com.checker.manager.rss.RssFeedClient;
import com.checker.model.dto.rss.RssSubscriptionRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RSS 订阅管理业务：维护订阅配置、触发轮询与批量下载，并读取抓取记录。
 *
 * <p>Web 层只做参数接收与响应包装，订阅数据读写统一经由本服务访问 DAO。</p>
 */
@Service
public class RssSubscriptionService {

    private final RssStore store;
    private final RssFeedClient feedClient;
    private final RssRuntime runtime;

    public RssSubscriptionService(RssStore store, RssFeedClient feedClient, RssRuntime runtime) {
        this.store = store;
        this.feedClient = feedClient;
        this.runtime = runtime;
    }

    /**
     * 读取全部未删除的订阅。
     */
    public List<Map<String, Object>> list() {
        return store.list();
    }

    /**
     * 新建订阅并返回订阅标识。
     */
    public String create(RssSubscriptionRequest request) {
        return store.save(null, request);
    }

    /**
     * 更新已有订阅并返回订阅标识。
     */
    public String update(String id, RssSubscriptionRequest request) {
        return store.save(id, request);
    }

    /**
     * 逻辑删除订阅。
     */
    public void delete(String id) {
        store.delete(id);
    }

    /**
     * 启用或暂停订阅。
     */
    public void enable(String id, boolean enabled) {
        store.enable(id, enabled);
    }

    /**
     * 立即触发一次订阅轮询，并按其当前修订号启动扫描。
     */
    public void poll(String id) {
        store.requestPoll(id);
        Map<String, Object> row = store.list().stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("订阅不存在或已被删除: " + id));
        runtime.startPoll(id, ((Number) row.get("revision")).intValue());
    }

    /**
     * 重试订阅下失败的下载任务。
     */
    public void retry(String id) {
        store.retry(id);
    }

    /**
     * 分页读取订阅下的抓取记录。
     */
    public Map<String, Object> items(String id, int page, int size) {
        return store.items(id, page, size);
    }

    /**
     * 读取订阅的最近轮询记录。
     */
    public List<Map<String, Object>> runs(String id) {
        return store.runs(id);
    }

    /**
     * 用给定的检索条件预览订阅结果，不落库。
     */
    public List<RssSearchItem> preview(RssSubscriptionRequest request) {
        return feedClient.fetch(request.getSearchOptions());
    }
}
