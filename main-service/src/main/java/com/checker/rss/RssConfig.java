package com.checker.rss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "rss")
public class RssConfig {
    private boolean enabled = true;
    private String hubUrl = "http://rsshub:1200";
    @Min(1) @Max(20) private int maxAttempts = 5;
    @Min(1) @Max(25) private int batchSize = 25;
    @Min(1) @Max(20) private int maxActiveBatches = 2;
}
