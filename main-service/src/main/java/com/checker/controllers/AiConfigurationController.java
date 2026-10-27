package com.checker.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.checker.common.Result;
import com.checker.dto.AiProviderRequest;
import com.checker.dto.AiProviderView;
import com.checker.dto.AiUseCaseRequest;
import com.checker.entity.AiProviderConfigEntity;
import com.checker.service.AiConfigurationService;
import com.checker.service.AiInternalClient;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供模型供应商、用途策略和提示词的管理接口，把配置校验与连接错误转换为前端可读的结果。
 */
@RestController
@RequestMapping("/api/ai/config")
@Tag(name = "AI 配置", description = "AI 服务商、模型与使用场景配置")
@PreAuthorize("hasRole('ADMIN')")
public class AiConfigurationController {
    private final AiConfigurationService configurationService;
    private final AiInternalClient internalClient;

    public AiConfigurationController(AiConfigurationService configurationService,
                                     AiInternalClient internalClient) {
        this.configurationService = configurationService;
        this.internalClient = internalClient;
    }

    /**
     * 列出可展示的供应商配置，不返回保存的密钥。
     */
    @GetMapping("/providers")
    public Result<List<AiProviderView>> providers() {
        return Result.success(configurationService.providers());
    }

    /**
     * 校验并新增一个模型供应商配置。
     */
    @PostMapping("/providers")
    public Result<AiProviderView> createProvider(@Valid @RequestBody AiProviderRequest request) {
        try {
            return Result.success(configurationService.create(request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    /**
     * 更新指定供应商的连接和权限设置。
     */
    @PutMapping("/providers/{id}")
    public Result<AiProviderView> updateProvider(@PathVariable long id,
                                                 @Valid @RequestBody AiProviderRequest request) {
        try {
            return Result.success(configurationService.update(id, request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    /**
     * 删除指定供应商，不存在时返回明确错误。
     */
    @DeleteMapping("/providers/{id}")
    public Result<Void> deleteProvider(@PathVariable long id) {
        try {
            configurationService.delete(id);
            return Result.success();
        } catch (IllegalArgumentException exception) {
            return Result.error(404, exception.getMessage());
        }
    }

    /**
     * 测试供应商连接并获取可选模型，失败时返回可读的连接原因。
     */
    @PostMapping("/providers/{id}/models")
    public Result<Map<String, Object>> models(@PathVariable long id) {
        try {
            AiProviderConfigEntity provider = configurationService.requireProvider(id);
            List<String> models = internalClient.models(provider);
            return Result.success(Map.of("connected", true, "models", models));
        } catch (Exception exception) {
            return Result.error(503, "无法连接模型服务: " + rootMessage(exception));
        }
    }

    /**
     * 读取各种 AI 任务当前使用的模型和分析策略。
     */
    @GetMapping("/use-cases")
    public Result<List<Map<String, Object>>> useCases() {
        return Result.success(configurationService.useCases());
    }

    /**
     * 保存某种 AI 任务的策略，并处理提示词版本等关联配置。
     */
    @PutMapping("/use-cases/{useCase}")
    public Result<Map<String, Object>> updateUseCase(@PathVariable String useCase,
                                                     @Valid @RequestBody AiUseCaseRequest request) {
        try {
            return Result.success(configurationService.updateUseCase(useCase, request));
        } catch (IllegalArgumentException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    /**
     * 读取指定 AI 用途的默认提示词，供用户在设置页面选择恢复。
     */
    @GetMapping("/use-cases/{useCase}/default-prompt")
    public Result<Map<String, String>> defaultPrompt(@PathVariable String useCase) {
        try {
            return Result.success(Map.of("prompt", configurationService.defaultPrompt(useCase)));
        } catch (IllegalArgumentException exception) {
            return Result.error(404, exception.getMessage());
        }
    }

    /**
     * 提取最内层失败原因，帮助用户理解模型连接测试为什么失败。
     */
    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
