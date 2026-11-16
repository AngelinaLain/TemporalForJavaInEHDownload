package com.checker.controllers;

import com.checker.common.Result;
import com.checker.rss.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/rss/subscriptions")
@PreAuthorize("hasRole('ADMIN')")
public class RssSubscriptionController {
    private final RssStore store;
    private final RssFeedClient feed;
    private final RssRuntime runtime;
    private final RssConfig config;

    public RssSubscriptionController(RssStore store, RssFeedClient feed, RssRuntime runtime, RssConfig config) {
        this.store = store;
        this.feed = feed;
        this.runtime = runtime;
        this.config = config;
    }

    @GetMapping
    public Result<?> list() {
        return Result.success(Map.of("records", store.list(), "enabled", config.isEnabled()));
    }

    @PostMapping
    public Result<?> create(@RequestBody RssSubscriptionRequest r) {
        return Result.success(Map.of("id", store.save(null, r)));
    }

    @PutMapping("/{id}")
    public Result<?> update(@PathVariable String id, @RequestBody RssSubscriptionRequest r) {
        return Result.success(Map.of("id", store.save(id, r)));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable String id) {
        store.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/enabled")
    public Result<?> enable(@PathVariable String id, @RequestBody Map<String, Boolean> r) {
        store.enable(id, Boolean.TRUE.equals(r.get("enabled")));
        return Result.success();
    }

    @PostMapping("/{id}/poll")
    public Result<?> poll(@PathVariable String id) {
        store.requestPoll(id);
        var row = store.list().stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
        runtime.startPoll(id, ((Number) row.get("revision")).intValue());
        return Result.success();
    }

    @PostMapping("/{id}/retry")
    public Result<?> retry(@PathVariable String id) {
        store.retry(id);
        return Result.success();
    }

    @GetMapping("/{id}/items")
    public Result<?> items(@PathVariable String id, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int size) {
        return Result.success(store.items(id, page, size));
    }

    @GetMapping("/{id}/runs")
    public Result<?> runs(@PathVariable String id) {
        return Result.success(store.runs(id));
    }

    @PostMapping("/preview")
    public Result<?> preview(@RequestBody RssSubscriptionRequest r) {
        r.validate();
        return Result.success(feed.fetch(r.getSearchOptions()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> invalid(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Result<?> unavailable(IllegalStateException e) {
        return Result.error(503, e.getMessage());
    }
}
