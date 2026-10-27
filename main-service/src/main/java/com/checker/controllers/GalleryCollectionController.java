package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.checker.common.Result;
import com.checker.dto.GalleryCollectionCandidate;
import com.checker.dto.GalleryCollectionItemRequest;
import com.checker.dto.GalleryCollectionRequest;
import com.checker.dto.GalleryCollectionSummary;
import com.checker.entity.GalleryCollectionEntity;
import com.checker.service.GalleryCollectionService;
import com.checker.service.KomgaSeriesSyncService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理本地画廊合集、成员和推荐候选，并提供合集到 Komga 系列的同步入口。
 */
@RestController
@RequestMapping("/api/collections")
@Tag(name = "图库集合", description = "集合的维护、图库归类与推荐")
@PreAuthorize("hasRole('ADMIN')")
public class GalleryCollectionController {
    private final GalleryCollectionService service;
    private final KomgaSeriesSyncService seriesSyncService;

    public GalleryCollectionController(GalleryCollectionService service,
                                       KomgaSeriesSyncService seriesSyncService) {
        this.service = service;
        this.seriesSyncService = seriesSyncService;
    }

    /**
     * 返回合集名称、说明和成员数量，供列表与筛选控件使用。
     */
    @GetMapping
    public Result<List<GalleryCollectionSummary>> list() {
        return Result.success(service.listCollections());
    }

    /**
     * 新增一个本地合集。
     */
    @PostMapping
    public Result<GalleryCollectionEntity> create(@Valid @RequestBody GalleryCollectionRequest request) {
        return execute(() -> service.create(request));
    }

    /**
     * 修改合集名称及说明。
     */
    @PutMapping("/{id}")
    public Result<GalleryCollectionEntity> update(@PathVariable long id,
                                                   @Valid @RequestBody GalleryCollectionRequest request) {
        return execute(() -> service.update(id, request));
    }

    /**
     * 删除本地合集，具体成员关系处理交给合集服务完成。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        return execute(() -> {
            service.delete(id);
            return null;
        });
    }

    /**
     * 读取指定合集的画廊成员。
     */
    @GetMapping("/{id}/items")
    public Result<List<GalleryCollectionCandidate>> items(@PathVariable long id) {
        return execute(() -> service.listItems(id));
    }

    /**
     * 把用户选定的一批画廊加入合集，并记录归组来源。
     */
    @PostMapping("/{id}/items")
    public Result<Void> addItems(@PathVariable long id,
                                 @Valid @RequestBody GalleryCollectionItemRequest request) {
        return execute(() -> {
            service.addItems(id, request.getGids(), request.getSource());
            return null;
        });
    }

    /**
     * 从指定合集中移除一个画廊成员。
     */
    @DeleteMapping("/{id}/items/{gid}")
    public Result<Void> removeItem(@PathVariable long id, @PathVariable long gid) {
        return execute(() -> {
            service.removeItem(id, gid);
            return null;
        });
    }

    /**
     * 根据合集已有成员寻找建议归入的画廊，并返回匹配理由。
     */
    @GetMapping("/{id}/suggestions")
    public Result<List<GalleryCollectionCandidate>> suggestions(@PathVariable long id,
                                                                 @RequestParam(defaultValue = "30") int limit) {
        return execute(() -> service.suggestions(id, limit));
    }

    /**
     * 搜索可加入合集的画廊，帮助用户手动选择成员。
     */
    @GetMapping("/gallery-search")
    public Result<List<GalleryCollectionCandidate>> gallerySearch(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "unassigned") String scope,
            @RequestParam(defaultValue = "30") int limit) {
        if (!List.of("all", "unassigned").contains(scope.toLowerCase())) {
            return Result.error(400, "scope 只支持 all 或 unassigned");
        }
        return Result.success(service.searchGalleries(keyword, scope, limit));
    }

    /**
     * 返回合集到 Komga 系列同步的进度。
     */
    @GetMapping("/komga-series-sync/status")
    public Result<Map<String, Object>> komgaSeriesSyncStatus() {
        return Result.success(seriesSyncService.status());
    }

    /**
     * 启动后台系列同步，让物理归档与本地合集关系对齐。
     */
    @PostMapping("/komga-series-sync")
    public Result<Map<String, Object>> komgaSeriesSync() {
        try {
            return Result.success(seriesSyncService.start());
        } catch (IllegalStateException failure) {
            return Result.error(409, failure.getMessage());
        }
    }

    /**
     * 统一把合集操作中的输入校验错误转换为接口响应。
     */
    private <T> Result<T> execute(Action<T> action) {
        try {
            return Result.success(action.run());
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Action<T> {
        T run();
    }
}
