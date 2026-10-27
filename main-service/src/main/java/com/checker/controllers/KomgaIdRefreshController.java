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

/**
 * 提供数据库旧 Komga ID 的刷新入口、进度查询和停止任务接口，只有管理员可以操作。
 */
@RestController
@RequestMapping("/api/komga-id-refresh")
@Tag(name = "Komga 关联刷新", description = "按物理归档唯一匹配，更新数据库中的失效 Komga Book ID")
@PreAuthorize("hasRole('ADMIN')")
public class KomgaIdRefreshController {
    private final KomgaIdRefreshService service;

    public KomgaIdRefreshController(KomgaIdRefreshService service) { this.service = service; }

    /**
     * 接收可选 GID 范围并启动后台刷新，输入无效或已有任务运行时返回明确业务错误。
     */
    @PostMapping
    public Result<Map<String, Object>> start(@RequestBody(required = false) RefreshRequest request) {
        try { return Result.success(service.start(request == null ? null : request.gids())); }
        catch (IllegalArgumentException invalid) { return Result.error(400, invalid.getMessage()); }
        catch (IllegalStateException conflict) { return Result.error(409, conflict.getMessage()); }
    }

    /**
     * 返回当前实例最近一次刷新任务的进度与有数量上限的结果明细。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() { return Result.success(service.status()); }

    /**
     * 按页面显示的任务 ID 请求停止；任务已更换时拒绝，避免旧页面误停新任务。
     */
    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancel(@RequestBody(required = false) CancelRequest request) {
        try { return Result.success(service.cancel(request == null ? null : request.jobId())); }
        catch (IllegalStateException changed) { return Result.error(409, changed.getMessage()); }
    }

    public record RefreshRequest(@JsonDeserialize(contentUsing = StrictGidDeserializer.class) List<Long> gids) {}
    public record CancelRequest(String jobId) {}

    /** 严格读取 GID 整数，防止把小数自动截断后更新了另一条画廊。 */
    public static final class StrictGidDeserializer extends JsonDeserializer<Long> {
        /**
         * 只接受整数形式的 GID，拒绝字符串、小数和溢出值，避免更新错误的画廊。
         */
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Long.class, "GID 必须使用整数 JSON 数值");
            return parser.getLongValue();
        }
    }
}
