package com.checker.controllers;

import com.checker.common.Result;
import com.checker.service.KomgaIdRefreshService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.io.IOException;

@RestController
@RequestMapping("/api/komga-id-refresh")
@Tag(name = "Komga 关联刷新", description = "按物理归档唯一匹配，更新数据库中的失效 Komga Book ID")
@PreAuthorize("hasRole('ADMIN')")
public class KomgaIdRefreshController {
    private final KomgaIdRefreshService service;

    public KomgaIdRefreshController(KomgaIdRefreshService service) { this.service = service; }

    @PostMapping
    public Result<Map<String, Object>> start(@RequestBody(required = false) RefreshRequest request) {
        try { return Result.success(service.start(request == null ? null : request.gids())); }
        catch (IllegalArgumentException invalid) { return Result.error(400, invalid.getMessage()); }
        catch (IllegalStateException conflict) { return Result.error(409, conflict.getMessage()); }
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() { return Result.success(service.status()); }

    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancel(@RequestBody(required = false) CancelRequest request) {
        try { return Result.success(service.cancel(request == null ? null : request.jobId())); }
        catch (IllegalStateException changed) { return Result.error(409, changed.getMessage()); }
    }

    public record RefreshRequest(@JsonDeserialize(contentUsing = StrictGidDeserializer.class) List<Long> gids) {}
    public record CancelRequest(String jobId) {}

    /** Jackson normally coerces fractional numbers to Long, which could update a different gallery. */
    public static final class StrictGidDeserializer extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Long.class, "GID 必须使用整数 JSON 数值");
            return parser.getLongValue();
        }
    }
}
