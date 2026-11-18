package com.checker.web.rss;

import com.checker.config.rss.RssConfig;
import com.checker.model.dto.rss.RssSubscriptionRequest;
import com.checker.service.rss.RssSubscriptionService;
import com.checker.shared.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RSS 订阅管理接口：只接收参数、做基本校验并包装响应，订阅读写交由服务层完成。
 */
@RestController
@RequestMapping("/api/rss/subscriptions")
@PreAuthorize("hasRole('ADMIN')")
public class RssSubscriptionController {

    private final RssSubscriptionService subscriptionService;
    private final RssConfig config;

    public RssSubscriptionController(RssSubscriptionService subscriptionService, RssConfig config) {
        this.subscriptionService = subscriptionService;
        this.config = config;
    }

    @GetMapping
    public Result<?> list() {
        return Result.success(Map.of("records", subscriptionService.list(), "enabled", config.isEnabled()));
    }

    @PostMapping
    public Result<?> create(@RequestBody RssSubscriptionRequest request) {
        return Result.success(Map.of("id", subscriptionService.create(request)));
    }

    @PutMapping("/{id}")
    public Result<?> update(@PathVariable String id, @RequestBody RssSubscriptionRequest request) {
        return Result.success(Map.of("id", subscriptionService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable String id) {
        subscriptionService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/enabled")
    public Result<?> enable(@PathVariable String id, @RequestBody Map<String, Boolean> request) {
        subscriptionService.enable(id, Boolean.TRUE.equals(request.get("enabled")));
        return Result.success();
    }

    @PostMapping("/{id}/poll")
    public Result<?> poll(@PathVariable String id) {
        subscriptionService.poll(id);
        return Result.success();
    }

    @PostMapping("/{id}/retry")
    public Result<?> retry(@PathVariable String id) {
        subscriptionService.retry(id);
        return Result.success();
    }

    @GetMapping("/{id}/items")
    public Result<?> items(@PathVariable String id,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "25") int size) {
        return Result.success(subscriptionService.items(id, page, size));
    }

    @GetMapping("/{id}/runs")
    public Result<?> runs(@PathVariable String id) {
        return Result.success(subscriptionService.runs(id));
    }

    @PostMapping("/preview")
    public Result<?> preview(@RequestBody RssSubscriptionRequest request) {
        request.validate();
        return Result.success(subscriptionService.preview(request));
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
