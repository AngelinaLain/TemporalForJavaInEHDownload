package com.checker.controllers;

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

@RestController
@RequestMapping("/api/ai/config")
@PreAuthorize("hasRole('ADMIN')")
public class AiConfigurationController {
    private final AiConfigurationService configurationService;
    private final AiInternalClient internalClient;

    public AiConfigurationController(AiConfigurationService configurationService,
                                     AiInternalClient internalClient) {
        this.configurationService = configurationService;
        this.internalClient = internalClient;
    }

    @GetMapping("/providers")
    public Result<List<AiProviderView>> providers() {
        return Result.success(configurationService.providers());
    }

    @PostMapping("/providers")
    public Result<AiProviderView> createProvider(@Valid @RequestBody AiProviderRequest request) {
        try {
            return Result.success(configurationService.create(request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    @PutMapping("/providers/{id}")
    public Result<AiProviderView> updateProvider(@PathVariable long id,
                                                 @Valid @RequestBody AiProviderRequest request) {
        try {
            return Result.success(configurationService.update(id, request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    @DeleteMapping("/providers/{id}")
    public Result<Void> deleteProvider(@PathVariable long id) {
        try {
            configurationService.delete(id);
            return Result.success();
        } catch (IllegalArgumentException exception) {
            return Result.error(404, exception.getMessage());
        }
    }

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

    @GetMapping("/use-cases")
    public Result<List<Map<String, Object>>> useCases() {
        return Result.success(configurationService.useCases());
    }

    @PutMapping("/use-cases/{useCase}")
    public Result<Map<String, Object>> updateUseCase(@PathVariable String useCase,
                                                     @Valid @RequestBody AiUseCaseRequest request) {
        try {
            return Result.success(configurationService.updateUseCase(useCase, request));
        } catch (IllegalArgumentException exception) {
            return Result.error(400, exception.getMessage());
        }
    }

    @GetMapping("/use-cases/{useCase}/default-prompt")
    public Result<Map<String, String>> defaultPrompt(@PathVariable String useCase) {
        try {
            return Result.success(Map.of("prompt", configurationService.defaultPrompt(useCase)));
        } catch (IllegalArgumentException exception) {
            return Result.error(404, exception.getMessage());
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
