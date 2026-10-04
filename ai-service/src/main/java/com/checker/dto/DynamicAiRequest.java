package com.checker.dto;

import java.util.List;

public record DynamicAiRequest(String baseUrl, String apiKey, String model, String prompt,
                               List<ImageInput> images, Double temperature, Integer timeoutSeconds) {
    public record ImageInput(String mimeType, String base64) {
    }
}
