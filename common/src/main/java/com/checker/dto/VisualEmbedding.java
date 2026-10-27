package com.checker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 在服务间传递图片特征向量，同时带上模型名称、版本和维度，避免混用不同模型的结果。
 */
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
