package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisualEmbedding implements Serializable {
    private String model;
    private String modelVersion;
    private int dimensions;
    private float[] values;
}
