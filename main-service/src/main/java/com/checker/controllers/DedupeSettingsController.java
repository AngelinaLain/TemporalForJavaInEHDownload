package com.checker.controllers;

import com.checker.common.DedupeSettings;
import com.checker.common.Result;
import com.checker.service.DedupeSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** 提供管理员使用的去重设置接口，严格校验输入并同时返回默认值供前端比较。 */
@RestController
@RequestMapping("/api/dedupe-settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "去重设置", description = "元数据去重与视觉指纹审核参数")
public class DedupeSettingsController {
    private static final Set<String> FIELDS = Set.of("reviewScore", "autoMatchScore", "titleSimilarityPercent",
            "visualPageDistance", "visualMinMatchedPages", "visualMinCoverage", "visualQualityDelta");
    private final DedupeSettingsService service;

    public DedupeSettingsController(DedupeSettingsService service) {
        this.service = service;
    }

    /** 查询全局参数及默认参数，不产生数据库写入。 */
    @GetMapping
    public Result<Map<String, DedupeSettings>> get() {
        return Result.success(view(service.current()));
    }

    /** 仅接受完整的整数参数对象，拒绝小数、字符串和遗漏字段，避免误保存。 */
    @PutMapping
    public Result<Map<String, DedupeSettings>> save(@RequestBody JsonNode request) {
        try {
            if (request == null || !request.isObject()) throw new IllegalArgumentException("请提交完整的去重参数对象");
            request.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("不支持的去重参数：" + field);
            });
            DedupeSettings settings = new DedupeSettings(integer(request, "reviewScore"), integer(request, "autoMatchScore"),
                    integer(request, "titleSimilarityPercent"), integer(request, "visualPageDistance"),
                    integer(request, "visualMinMatchedPages"), integer(request, "visualMinCoverage"),
                    integer(request, "visualQualityDelta"));
            return Result.success(view(service.save(settings)));
        } catch (IllegalArgumentException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    private Map<String, DedupeSettings> view(DedupeSettings settings) {
        return Map.of("settings", settings, "defaults", DedupeSettings.defaults());
    }

    /** JsonNode 保留原始数值类型，避免默认反序列化把 85.9 或字符串自动转换成整数。 */
    private int integer(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("参数 " + field + " 必须是整数且不能为空");
        }
        return value.intValue();
    }
}
