package com.checker.controllers;

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

@RestController
@RequestMapping("/api/collections")
@PreAuthorize("hasRole('ADMIN')")
public class GalleryCollectionController {
    private final GalleryCollectionService service;
    private final KomgaSeriesSyncService seriesSyncService;

    public GalleryCollectionController(GalleryCollectionService service,
                                       KomgaSeriesSyncService seriesSyncService) {
        this.service = service;
        this.seriesSyncService = seriesSyncService;
    }

    @GetMapping
    public Result<List<GalleryCollectionSummary>> list() {
        return Result.success(service.listCollections());
    }

    @PostMapping
    public Result<GalleryCollectionEntity> create(@Valid @RequestBody GalleryCollectionRequest request) {
        return execute(() -> service.create(request));
    }

    @PutMapping("/{id}")
    public Result<GalleryCollectionEntity> update(@PathVariable long id,
                                                   @Valid @RequestBody GalleryCollectionRequest request) {
        return execute(() -> service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        return execute(() -> {
            service.delete(id);
            return null;
        });
    }

    @GetMapping("/{id}/items")
    public Result<List<GalleryCollectionCandidate>> items(@PathVariable long id) {
        return execute(() -> service.listItems(id));
    }

    @PostMapping("/{id}/items")
    public Result<Void> addItems(@PathVariable long id,
                                 @Valid @RequestBody GalleryCollectionItemRequest request) {
        return execute(() -> {
            service.addItems(id, request.getGids(), request.getSource());
            return null;
        });
    }

    @DeleteMapping("/{id}/items/{gid}")
    public Result<Void> removeItem(@PathVariable long id, @PathVariable long gid) {
        return execute(() -> {
            service.removeItem(id, gid);
            return null;
        });
    }

    @GetMapping("/{id}/suggestions")
    public Result<List<GalleryCollectionCandidate>> suggestions(@PathVariable long id,
                                                                 @RequestParam(defaultValue = "30") int limit) {
        return execute(() -> service.suggestions(id, limit));
    }

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

    @GetMapping("/komga-series-sync/status")
    public Result<Map<String, Object>> komgaSeriesSyncStatus() {
        return Result.success(seriesSyncService.status());
    }

    @PostMapping("/komga-series-sync")
    public Result<Map<String, Object>> komgaSeriesSync() {
        try {
            return Result.success(seriesSyncService.start());
        } catch (IllegalStateException failure) {
            return Result.error(409, failure.getMessage());
        }
    }

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
