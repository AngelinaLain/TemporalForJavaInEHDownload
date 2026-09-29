package com.checker.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record AiUseCaseRequest(
        Long providerId,
        String model,
        @NotBlank String analysisMode,
        Boolean allowImageTransmission,
        Boolean includeMetadata,
        @Min(1) @Max(8) Integer samplePageCount,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal ambiguousMinSimilarity,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal highSimilarity,
        String prompt) {
}
