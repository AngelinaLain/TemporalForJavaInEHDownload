package com.checker.controllers;

import com.checker.common.PerceptualHash;
import com.checker.common.Result;
import com.checker.entity.VisualRefreshJobEntity;
import com.checker.service.VisualHistoryRefreshService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/visual-dedup")
@PreAuthorize("hasRole('ADMIN')")
public class VisualDeduplicationController {
    private final VisualHistoryRefreshService refreshService;

    public VisualDeduplicationController(VisualHistoryRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithmVersion", PerceptualHash.ALGORITHM_VERSION);
        payload.put("fingerprintedGalleries", refreshService.fingerprintedGalleries());
        payload.put("latestJob", refreshService.latest());
        return Result.success(payload);
    }

    @PostMapping("/refresh")
    public Result<VisualRefreshJobEntity> refresh(@RequestBody(required = false) Map<String, Boolean> request) {
        try {
            boolean force = request != null && Boolean.TRUE.equals(request.get("force"));
            return Result.success(refreshService.start(force));
        } catch (IllegalStateException exception) {
            return Result.error(409, exception.getMessage());
        }
    }
}
