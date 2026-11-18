package com.checker.web.komga;

import com.checker.service.komga.KomgaImportReviewService;
import com.checker.shared.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 展示等待或失败的 Komga 入库记录，并允许只重试扫描确认而不重新下载归档。
 */
@RestController
@RequestMapping("/api/komga-import-reviews")
@Tag(name = "Komga 导入审核", description = "Komga 导入失败记录查询与重试")
@PreAuthorize("hasRole('ADMIN')")
public class KomgaImportReviewController {

    private final KomgaImportReviewService reviewService;

    public KomgaImportReviewController(KomgaImportReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 分页读取复核记录，同时返回失败与等待数量。
     */
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "KOMGA_IMPORT_FAILED") String status) {
        try {
            return Result.success(reviewService.listReviews(page, size, status));
        } catch (IllegalArgumentException failure) {
            return Result.error(400, failure.getMessage());
        }
    }

    /**
     * 认领记录并启动只做 Komga 确认的工作流。
     */
    @PostMapping("/{gid}/retry")
    public Result<Map<String, String>> retry(@PathVariable Long gid) {
        KomgaImportReviewService.RetryOutcome outcome = reviewService.retry(gid);
        return switch (outcome.status()) {
            case STARTED -> Result.success(outcome.payload());
            case NOT_FOUND -> Result.error(404, outcome.message());
            case CONFLICT -> Result.error(409, outcome.message());
            case START_FAILED -> Result.error(outcome.message());
        };
    }
}
