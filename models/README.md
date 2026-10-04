# 本地视觉模型

将 DINOv2-small 的 ONNX 模型放在此目录。当前默认使用
`dinov2-small-ONNX_int8.onnx`，它在 CPU 推理速度、体积和特征一致性之间最均衡。

模型约定：

- 输入为 `float32`、NCHW、`1x3x224x224`。
- 使用 ImageNet mean/std 归一化。
- 输出可以是 `[1, 384]`，也可以是 `[1, tokens, 384]`；后一种取 CLS token。
- 模型文件不会提交 Git，部署时由 `docker-compose.yml` 只读挂载到 `/models`。

配置好文件后设置：

```properties
AI_EMBEDDING_ENABLED=true
AI_EMBEDDING_MODEL_PATH=/models/dinov2-small-ONNX_int8.onnx
AI_EMBEDDING_MODEL_NAME=dinov2-small-int8
AI_EMBEDDING_MODEL_VERSION=dinov2-small-int8-v1
AI_CONFIG_MASTER_KEY=请使用独立的高强度随机密钥
```

如果更换模型权重或预处理规则，请同步修改 `AI_EMBEDDING_MODEL_VERSION`，使已有缓存安全失效。

## 兼容性验证

在 Linux CPU、ONNX Runtime 1.29.0、224x224 输入下，目录内 8 个模型均可由生产代码加载，
并输出有限、L2 归一化的 384 维向量。下面的耗时为单次本地测试，仅用于同机型间的相对比较；
`cosineToFp32` 是同一张合成测试图与 FP32 输出的余弦相似度，不代表真实画廊数据集上的召回率。

| 模型 | 大小 | 热推理均值 | cosineToFp32 |
| --- | ---: | ---: | ---: |
| FP32 | 88.5 MB | 52.20 ms | 1.0000 |
| FP16 | 45.5 MB | 101.19 ms | 0.9999 |
| INT8 | 24.4 MB | 44.59 ms | 0.9539 |
| Q4 | 16.9 MB | 42.44 ms | 0.9477 |
| Q4F16 | 14.9 MB | 70.77 ms | 0.9475 |
| BNB4 | 15.5 MB | 53.06 ms | 0.9377 |
| quantized / UINT8 | 24.4 MB | 44.67 / 50.02 ms | 0.9342 |

`dinov2-small-ONNX_quantized.onnx` 与 `dinov2-small-ONNX_uint8.onnx` 的 SHA-256 完全相同，
两者是重复文件，部署时保留一个即可。CPU 默认建议 INT8；如果更重视与 FP32 的一致性且能接受更大模型，
使用 FP32。FP16 在本次 CPU 测试中没有速度优势。

可通过以下环境变量启用真实模型兼容性测试（普通单元测试默认跳过）：

```properties
AI_MODEL_TEST_DIR=/models
```
