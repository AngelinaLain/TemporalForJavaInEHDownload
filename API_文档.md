# GalleryImport REST API 文档

本文档以当前控制器源码为准，覆盖 `main-service` 的全部 REST 端点以及 `ai-service` 的内部端点。

## 1. 约定

### 1.1 地址与认证

- 主服务：`http://127.0.0.1:8001`
- AI 服务：`http://127.0.0.1:8082`
- 管理前端通过同源 `/api` 访问主服务。
- `POST /api/auth/login` 无需认证；主服务其他业务端点均需管理员 JWT。
- 请求头：`Authorization: Bearer <jwt-token>`
- JSON 请求应携带：`Content-Type: application/json`
- 交互式文档：`http://127.0.0.1:8001/swagger-ui.html`，OpenAPI JSON 为 `/v3/api-docs`。页面已定义 JWT Bearer 认证，点击 Authorize 填入 Token 即可直接调用。

### 1.2 主服务响应

主服务使用统一业务响应。除 Spring Security 在认证/授权失败时直接返回 HTTP `401/403` 外，控制器中的业务错误通常仍是 HTTP `200`，调用方应以响应体 `code` 判断成功与否。

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {},
  "timestamp": 1790123456789
}
```

| `code` | 含义 | 常见场景 |
| ---: | --- | --- |
| `200` | 成功 | 请求已完成或异步任务已启动 |
| `400` | 参数错误 | 状态、筛选值、请求体不合法 |
| `401` | 未认证 | 凭据错误、Token 缺失/过期/已注销 |
| `403` | 无权限 | 当前身份不是管理员 |
| `404` | 资源不存在 | GID 或审核对象不存在 |
| `409` | 状态冲突 | 任务正在运行、记录已被其他任务认领 |
| `429` | 请求过多 | 登录失败次数超限 |
| `500` | 服务错误 | Temporal、Komga、数据库等下游调用失败 |

分页接口会把 `page` 修正为至少 `1`，把 `size` 限制在 `1–100`。

### 1.3 快速调用

```bash
curl -X POST http://127.0.0.1:8001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your_password"}'

curl http://127.0.0.1:8001/api/dashboard/stats \
  -H "Authorization: Bearer <jwt-token>"
```

## 2. 认证

### `POST /api/auth/login`

请求体：

```json
{ "username": "admin", "password": "your_password" }
```

`username` 必填且最长 64 字符，`password` 必填且最长 256 字符。成功时 `data` 为：

```json
{ "token": "<jwt-token>", "username": "admin" }
```

连续失败可能返回业务码 `429`，封禁周期为 15 分钟。

### `POST /api/auth/logout`

将当前 JWT 的 JTI 加入黑名单，使 Token 立即失效。Redis 不可用时使用本地缓存降级。无需请求体。

## 3. 自动化与 Komga 批处理

### `POST /api/temporal/eh/start`

启动完整抓取和导入工作流。请求体：

```json
{
  "keyword": "language:chinese",
  "filterCats": 0,
  "minimumRating": 1,
  "language": "chinese",
  "pageAtLeast": 10,
  "pageAtMost": 100,
  "searchExpungedGalleries": false,
  "showOnlyWithTorrents": false,
  "disableLanguageFilter": false,
  "disableUploaderFilter": false,
  "disableTagsFilter": false
}
```

`keyword` 与 `galleryUrls` 至少提供其一；其余字段可省略并采用示例中的默认值，页数上下限默认为空。成功时返回 `workflowId` 和 `runId`。

按 GID 直接抓取必须同时携带画廊 token。传入 `galleryUrls`（完整画廊链接，或 `GID/token`）后，系统获取元数据并进入正常的去重、下载与入库流程：

```json
{ "galleryUrls": ["https://e-hentai.org/g/123456/abcdef1234/", "234567/fedcba4321"] }
```

### `POST /api/temporal/eh/retry-failed`

异步重试数据库中的下载失败任务。无请求体，返回 `workflowId` 和 `runId`。

### `POST /api/temporal/eh/test-email`

发送测试邮件。请求体可省略，也可传：

```json
{ "subject": "邮件测试", "content": "测试内容" }
```

### `POST /api/temporal/eh/collections/build-by-tags`

按标签创建或更新 Komga 合集。该端点是旧的“按标签直接构建”能力；自定义合集管理请使用第 10 节接口。

```json
{
  "collectionName": "纯爱 / Vanilla",
  "tags": ["female:sole female", "male:sole male"],
  "matchAllTags": false
}
```

`collectionName` 和非空 `tags` 必填；`matchAllTags=false` 表示任一标签命中即可。

### Komga 维护端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/temporal/eh/sync-tags` | 后台同步数据库标签到 Komga Book 与 Series |
| POST | `/api/temporal/eh/batch-refresh-metadata` | 后台刷新全部已入库书籍的 Komga 元数据 |
| POST | `/api/temporal/eh/batch-update-filesize` | 后台补齐缺失或为 0 的 `file_size_mb` |

以上三个端点均无请求体，并在后台异步执行。

## 4. Dashboard 与画廊查询

### 统计和状态

| 方法 | 路径 | `data` 说明 |
| --- | --- | --- |
| GET | `/api/dashboard/stats` | `total`、`downloaded`、`imported`、`failed`、`pending`、`totalSizeGb` |
| GET | `/api/dashboard/status-distribution` | `[{name, value}]` 状态分布 |
| GET | `/api/dashboard/download-progress` | 下载中项目的 `gid`、`title`、字节数、大小和百分比 |
| GET | `/api/dashboard/db-status` | `connected`、`total`、`totalSizeGb`，失败时含 `error` |
| GET | `/api/dashboard/file-size-distribution` | `{labels, data}` 文件大小分桶 |
| GET | `/api/dashboard/crawl-timeline` | `{dates, counts}` 最近 60 个有数据日期的抓取量 |
| GET | `/api/dashboard/tag-stats` | Top 20 标签命名空间统计 `[{name,nameCn,value}]` |

### `GET /api/dashboard/galleries`

分页查询画廊。

| Query 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `page` | `1` | 页码 |
| `size` | `20` | 每页数量，最大 100 |
| `status` | 空 | 英文枚举或中文状态名 |
| `keyword` | 空 | 标题或文件名模糊搜索 |
| `tag` | 空 | 完整标签精确匹配 |
| `dedupe` | `preferred` | `preferred`、`duplicates` 或 `all` |
| `sortBy` | `crawledAt` | `crawledAt`、`gid`、`title`、`downloadStatus`、`fileSizeMb` |
| `sortOrder` | `desc` | `asc` 或 `desc`；其他值按降序处理 |

响应 `data` 是 MyBatis-Plus 分页结构，主要字段为 `records`、`total`、`size`、`current`、`pages`。支持的状态为：

`PENDING`、`DOWNLOADING`、`DOWNLOADED`、`WAITING_KOMGA`、`PARTIAL`、`DOWNLOAD_FAILED`、`KOMGA_IMPORT_FAILED`、`IMPORTED`、`REVIEW_REQUIRED`、`BLOCKED`、`IGNORED`。

### 搜索和标签

| 方法 | 路径 | 参数/说明 |
| --- | --- | --- |
| GET | `/api/dashboard/suggestions` | `q` 必填；`limit=10`，最大 30；`type=all\|title\|tag` |
| GET | `/api/dashboard/tag-translations` | 返回完整的英文标签到中文名称映射 |
| POST | `/api/dashboard/tag-translations/refresh` | 刷新标签翻译缓存，无请求体 |
| GET | `/api/dashboard/tag-detail` | `tag` 必填；返回 `tag`、`name`、`intro` |

## 5. 重复项人工审核

### `GET /api/dedupe-reviews`

Query：`page=1`、`size=20`、`decision=PENDING`。`decision` 可使用 `PENDING`、`MATCH`、`DIFFERENT`、`VARIANT` 或 `ALL`。

响应 `data` 包含 `records`、`total`、`page`、`size`、`pendingCount`。记录包含匹配分数/原因、视觉证据、推荐 GID、审核信息，以及完整的 `left`、`right` 画廊对象。

### `POST /api/dedupe-reviews/{id}/resolve`

判定为同一作品：

```json
{ "decision": "MATCH", "preferredGid": 123 }
```

判定为不同作品：

```json
{ "decision": "DIFFERENT" }
```

也可提交 `{"decision":"VARIANT"}`，表示相关但应保留为独立版本。`MATCH` 时 `preferredGid` 必须是审核记录左右两侧之一。响应包含 `reviewId`、`decision`、`dispatchGids`，以及实际启动的 `workflows`。

### `POST /api/dedupe-reviews/{id}/ai-review`

对该审核记录的两个候选版本发起 AI 视觉复核，无需请求体。相同候选对已有 `QUEUED` 或 `RUNNING` 的任务时会复用该任务，避免重复提交。响应包含 `jobId` 和 `workflowId`，后续进度用 `GET /api/visual-dedup/ai-review/{jobId}` 查询。审核记录不存在时返回业务码 `400`，Temporal 工作流启动失败时返回 `503`。

该结果仅作为人工审核证据，**不会自动提交 MATCH / DIFFERENT / VARIANT 结论**。

## 6. Komga 入库复核

### `GET /api/komga-import-reviews`

Query：`page=1`、`size=20`、`status=KOMGA_IMPORT_FAILED`。`status` 支持 `KOMGA_IMPORT_FAILED`、`WAITING_KOMGA`、`DOWNLOADED` 或 `ALL`，也接受对应中文状态。

响应包含 `records`、`total`、`page`、`size`、`failedCount`、`waitingCount`。记录字段包括 `gid`、`title`、`filename`、`galleryUrl`、`downloadStatus`、`komgaBookId`、`confirmationAttempts`、`lastConfirmationAt`、`confirmationReason`、`candidateBookIds`。

### `POST /api/komga-import-reviews/{gid}/retry`

仅重新执行 Komga 扫描与入库确认，不重新下载。仅 `KOMGA_IMPORT_FAILED` 或 `DOWNLOADED` 状态可重试；成功时返回 `gid`、`workflowId`、`runId`。

## 7. 视觉去重

| 方法 | 路径 | 请求/响应 |
| --- | --- | --- |
| GET | `/api/visual-dedup/status` | 返回 `algorithmVersion`、`fingerprintedGalleries`、`latestJob`、`failedGalleries` |
| POST | `/api/visual-dedup/refresh` | 可选请求体 `{"force":false}`；启动历史指纹刷新并返回任务 |
| POST | `/api/visual-dedup/refresh/retry` | 请求体 `{"gids":[123,456]}`；重试指定失败项并返回任务 |
| POST | `/api/visual-dedup/ai-review` | 请求体 `{"leftGid":123,"rightGid":456}`；发起 AI 视觉复核，返回 `jobId` 与 `workflowId` |
| GET | `/api/visual-dedup/ai-review/{jobId}` | 返回 `{job, result}`；任务不存在时返回业务码 `404` |

已有刷新任务运行时返回业务码 `409`；重试列表非法时返回 `400`。

### AI 视觉复核任务

`POST /api/visual-dedup/ai-review` 与去重审核页的入口等价：两个 GID 必须不同、均为正数且存在于数据库。相同候选对已有 `QUEUED` 或 `RUNNING` 任务时复用该任务；工作流启动失败会返回 `503` 并把任务标记为 `FAILED`。

任务执行要求两侧画廊都已生成当前算法版本的页面视觉指纹，并依赖 `ai-service` 的本地向量模型；任一条件不满足时任务以 `FAILED` 结束，原因见 `lastError`。

任务状态取值：

| 状态 | 含义 |
| --- | --- |
| `QUEUED` | 已创建，等待 Activity 执行 |
| `RUNNING` | 正在计算向量与比对采样页 |
| `COMPLETED` | 完成，结果已写入 |
| `COMPLETED_WITH_WARNINGS` | 本地向量判断完成，但 LLM 复核失败，`lastError` 记录原因 |
| `FAILED` | 任务失败，`lastError` 记录原因 |

`GET /api/visual-dedup/ai-review/{jobId}` 的 `data` 包含两个字段：`job` 是任务记录（`id`、`workflowId`、`leftGid`、`rightGid`、`status`、`analysisMode`、阈值、`lastError`、各时间戳），`result` 是结果记录，未完成时为 `null`。`result` 字段：

| 字段 | 说明 |
| --- | --- |
| `decision` | `SAME_CONTENT`、`POSSIBLE_VARIANT`、`DIFFERENT_CONTENT` 或 `INSUFFICIENT_EVIDENCE` |
| `confidence` | `0–1` 置信度；`DIFFERENT_CONTENT` 时取 `1 −` 相似度加权值，表示“不同”的可信度 |
| `embeddingSimilarity` | 采样页向量平均余弦相似度 |
| `perceptualHashSimilarity` | 采样页感知哈希相似度 |
| `matchedPages` / `comparedPages` | 达到模糊下限的页数与参与比对的页数 |
| `pageOrderConsistency` | 页序一致性 |
| `reason` | 中文判断原因，LLM 复核成功时替换为模型给出的原因 |
| `requiresHumanReview` | 本地判断固定为 `true`；LLM 复核模式沿用模型返回值，但仍需管理员处置 |
| `llmUsed` / `imagesTransmitted` | 是否调用 LLM、是否实际发送了图片 |

## 8. AI 配置

端点前缀 `/api/ai/config`，全部要求管理员 JWT。配置保存在数据库（迁移 `V20`），保存后立即生效，不需要重启服务。

### 供应商

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ai/config/providers` | 返回全部供应商，按名称排序 |
| POST | `/api/ai/config/providers` | 新建供应商 |
| PUT | `/api/ai/config/providers/{id}` | 更新供应商 |
| DELETE | `/api/ai/config/providers/{id}` | 删除供应商；不存在时返回业务码 `404` |
| POST | `/api/ai/config/providers/{id}/models` | 用已保存的凭据探测可用模型列表 |

创建/更新请求体：

```json
{
  "name": "本地 Ollama",
  "scope": "LOCAL",
  "baseUrl": "http://ollama:11434",
  "apiKey": "sk-...",
  "defaultModel": "qwen2.5-vl",
  "allowTextMetadata": true,
  "allowVisualInput": false,
  "requestTimeoutSeconds": 120,
  "maxConcurrency": 2,
  "enabled": true
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 全局唯一，重名返回业务码 `400` |
| `scope` | 是 | `LOCAL` 或 `REMOTE` |
| `baseUrl` | 是 | 必须是不带 userinfo 的 HTTP/HTTPS 地址；末尾 `/` 会被去掉 |
| `apiKey` | 否 | 留空表示保持现有密钥；以 AES-GCM 加密后入库，任何接口都不回显明文 |
| `defaultModel` | 否 | 默认模型名 |
| `allowTextMetadata` | 否 | 是否允许把标题/标签发送给该供应商，默认 `true` |
| `allowVisualInput` | 否 | 是否允许发送图片，默认 `false`；`VISION_LLM` 模式必需 |
| `requestTimeoutSeconds` | 否 | `5–600`，默认 `120` |
| `maxConcurrency` | 否 | `1–32`，默认 `2` |
| `enabled` | 否 | 默认 `true`；禁用后不能用于新任务 |

响应中的供应商对象包含 `id`、`name`、`scope`、`protocol`（固定 `OPENAI_COMPATIBLE`）、`baseUrl`、`hasApiKey`、`defaultModel`、`allowTextMetadata`、`allowVisualInput`、`requestTimeoutSeconds`、`maxConcurrency`、`enabled`。

`POST /providers/{id}/models` 成功时返回 `{connected: true, models: [...]}`；供应商不存在或连不上模型服务时返回业务码 `503`。

### 用例

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ai/config/use-cases` | 返回 `SUMMARY`、`TAG_TRANSLATION`、`VISUAL_DUPLICATE_REVIEW` 三个用例的当前配置 |
| PUT | `/api/ai/config/use-cases/{useCase}` | 更新用例配置 |
| GET | `/api/ai/config/use-cases/{useCase}/default-prompt` | 返回该用例的内置默认提示词 |

更新请求体：

```json
{
  "providerId": 1,
  "model": "qwen2.5-vl",
  "analysisMode": "EMBEDDING_ONLY",
  "allowImageTransmission": false,
  "includeMetadata": false,
  "samplePageCount": 4,
  "ambiguousMinSimilarity": 0.86,
  "highSimilarity": 0.94,
  "prompt": "可选的提示词正文"
}
```

| 字段 | 说明 |
| --- | --- |
| `analysisMode` | 必填，`EMBEDDING_ONLY`、`EMBEDDING_TEXT_LLM` 或 `VISION_LLM` |
| `allowImageTransmission` | 只有 `VISION_LLM` 可设为 `true`，且所选供应商必须允许视觉输入 |
| `includeMetadata` | 是否把标题和标签一并送入 LLM |
| `samplePageCount` | `1–8`，`VISION_LLM` 发送的抽样页上限 |
| `ambiguousMinSimilarity` / `highSimilarity` | 取值 `0–1`，模糊下限必须小于高相似阈值 |
| `prompt` | 非空时生成新的提示词版本，历史版本保留 |

响应在请求字段之外还包含 `promptVersionId`、`promptVersion`（版本号）和 `prompt`（当前生效正文）。参数非法或阈值倒置时返回业务码 `400`。

用例首次访问时自动初始化，不需要手工建表或插入数据。`SUMMARY` 与 `TAG_TRANSLATION` 影响画廊简介生成与标签翻译，`VISUAL_DUPLICATE_REVIEW` 供第 7 节的 AI 视觉复核使用。

## 9. 群晖归档同步

### 状态与扫描

| 方法 | 路径 | 请求/响应 |
| --- | --- | --- |
| GET | `/api/archive-sync/status` | 返回扫描任务状态和归档统计 |
| POST | `/api/archive-sync/scan` | 启动归档文件扫描，无请求体 |
| POST | `/api/archive-sync/cover-match` | 可选 `{"gids":[123,456]}`；为空时处理服务选定的待匹配项 |

重复启动同类任务会返回业务码 `409`。

### `GET /api/archive-sync/reviews`

Query：`page=1`、`size=20`、`status=ACTIVE`。`ACTIVE` 包含 `PENDING`、`SYNCING`、`FAILED`；`ALL` 不过滤，其余值按具体状态过滤。

记录主要字段：`gid`、`title`、`expectedFilename`、`selectedFilename`、`matchType`、`status`、`message`、`coverStatus`、`coverMessage`、`coverCheckedAt`、`galleryUrl`、`candidates`。每个候选包含 `filename`、`coverScore`、`databaseGid`。

### 处置端点

| 方法 | 路径 | 请求/说明 |
| --- | --- | --- |
| POST | `/api/archive-sync/{gid}/synchronize` | `{"filename":"实际文件.cbz"}`；确认文件并同步数据库/归档状态 |
| POST | `/api/archive-sync/{gid}/redownload` | 认领记录并启动单画廊重新下载；返回工作流标识 |

## 10. 自定义画廊合集

### 合集 CRUD

| 方法 | 路径 | 请求/响应 |
| --- | --- | --- |
| GET | `/api/collections` | 返回合集摘要：`id`、`name`、`description`、`itemCount`、`updatedAt` |
| POST | `/api/collections` | 创建合集，请求体见下方 |
| PUT | `/api/collections/{id}` | 更新合集，请求体同创建 |
| DELETE | `/api/collections/{id}` | 删除合集 |

创建/更新请求：

```json
{ "name": "合集名称", "description": "可选说明" }
```

`name` 必填且最长 200 字符；`description` 最长 1000 字符。

### 成员与建议

| 方法 | 路径 | 参数/请求 |
| --- | --- | --- |
| GET | `/api/collections/{id}/items` | 返回合集成员 |
| POST | `/api/collections/{id}/items` | `{"gids":[123,456],"source":"MANUAL"}` |
| DELETE | `/api/collections/{id}/items/{gid}` | 移除单个成员 |
| GET | `/api/collections/{id}/suggestions` | `limit=30`，返回标题、封面、元数据综合匹配建议 |
| GET | `/api/collections/gallery-search` | `keyword` 可选，`scope=unassigned\|all`，`limit=30` |

成员/候选字段包括 `gid`、`title`、`originalTitle`、`galleryUrl`、`pageCount`、`rating`、合集信息，以及建议场景下的 `score`、`titleSimilarity`、`coverSimilarity`、`metadataSimilarity`、`reason`。

### Komga Series 同步

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/collections/komga-series-sync/status` | 查询最近或当前同步任务状态 |
| POST | `/api/collections/komga-series-sync` | 启动合集到 Komga Series 的同步 |

同步任务已运行时返回业务码 `409`。

## 11. Temporal 监控

### `GET /api/temporal/monitor/workflows`

读取最近最多 1000 个工作流并按父子关系返回树。节点包含 `workflowId`、`runId`、`type`、`status`、`startTime`、`closeTime`、`historyLength`、`children`。

### `GET /api/temporal/monitor/workflows/{workflowId}/history`

可选 Query：`runId`。返回按事件顺序排列的可读日志：`eventId`、Unix 秒时间戳 `time`、`type`、`level`、`message`。

### `POST /api/temporal/monitor/workflows/{workflowId}/terminate`

可选 Query：`runId`、`reason`。默认原因为“用户在监控页面手动终止”。该操作不可撤销。

## 12. AI 服务内部接口

AI 服务端点不使用主服务的 `Result` 包装，而是直接返回 HTTP 状态和响应体。管理前端不直接调用这些端点，均由 `main-service` 转发。

`/api/ai/dynamic/**` 与 `/api/ai/embedding/**` 需要内部令牌请求头：

```text
X-AI-Internal-Token: <AI_INTERNAL_TOKEN>
```

`AI_INTERNAL_TOKEN` 少于 16 个字符时这两个前缀直接返回 HTTP `503`；令牌缺失或错误返回 `401`。`/api/ai/generate-summary` 与 `/api/ai/batch-translate-tags` 目前不校验该请求头。

### `POST /api/ai/generate-summary`

```json
{ "title": "画廊标题", "tags": ["language:chinese", "female:..."] }
```

成功响应是纯文本中文简介。AI 节点不可用时返回 HTTP `503` 和错误文本，其他内部错误返回 `500`。

### `POST /api/ai/batch-translate-tags`

```json
{ "tags": ["artist:aaa", "language:chinese"] }
```

成功响应为输入标签到中文翻译的 JSON 对象；空列表返回 `{}`。AI 节点不可用时返回 HTTP `503`，内部错误返回 `500`。

### `POST /api/ai/dynamic/models`

向任意 OpenAI 兼容端点探测模型列表：

```json
{ "baseUrl": "http://ollama:11434", "apiKey": "sk-...", "timeoutSeconds": 30 }
```

成功返回 `{"models":["qwen2.5-vl"]}`；参数非法返回 `400`，连接失败返回 `503`。

### `POST /api/ai/dynamic/chat`

```json
{
  "baseUrl": "http://ollama:11434",
  "apiKey": "sk-...",
  "model": "qwen2.5-vl",
  "prompt": "请依据证据判断……",
  "images": [{ "mimeType": "image/jpeg", "base64": "..." }],
  "temperature": 0.2,
  "timeoutSeconds": 120
}
```

`images` 可省略。成功返回 `{"content":"模型输出文本"}`；参数非法返回 `400`，模型调用失败返回 `503`。

### `GET /api/ai/embedding/status`

返回 `enabled`、`available`、`model`、`modelVersion`、`inputSize`、`modelPath`。只有 `available` 为 `true` 时视觉复核才能执行，该字段同时要求功能开启且模型文件存在。

### `POST /api/ai/embedding`

`multipart/form-data`，字段名 `image`，单文件上限 20 MB。成功返回：

```json
{ "model": "dinov2-small-int8", "modelVersion": "dinov2-small-int8-v1", "dimensions": 384, "values": [] }
```

`values` 为已做 L2 归一化的浮点向量。图片为空、无法解码或超过像素上限返回 `400`；功能未启用或模型缺失返回 `503`。

## 13. Actuator

| 路径 | 认证 | 说明 |
| --- | --- | --- |
| `/actuator/health` | 无 | 健康检查 |
| `/actuator/info` | 无 | 应用信息 |
| `/actuator/prometheus` | 无 | Prometheus 指标 |
| `/actuator/**` 其他端点 | 管理员 JWT | Spring Boot 运维端点 |

Redis 探测也计入健康检查：Redis 不可达时 `/actuator/health` 会变为 `DOWN`。确认不依赖 Redis（已降级为进程内实现）时，可设置 `MANAGEMENT_HEALTH_REDIS_ENABLED=false` 关闭该项。

## 14. 完整端点索引

| 分组 | 方法 | 路径 |
| --- | --- | --- |
| 认证 | POST | `/api/auth/login` |
| 认证 | POST | `/api/auth/logout` |
| 自动化 | POST | `/api/temporal/eh/start` |
| 自动化 | POST | `/api/temporal/eh/retry-failed` |
| 自动化 | POST | `/api/temporal/eh/test-email` |
| Komga | POST | `/api/temporal/eh/collections/build-by-tags` |
| Komga | POST | `/api/temporal/eh/sync-tags` |
| Komga | POST | `/api/temporal/eh/batch-refresh-metadata` |
| Komga | POST | `/api/temporal/eh/batch-update-filesize` |
| Dashboard | GET | `/api/dashboard/stats` |
| Dashboard | GET | `/api/dashboard/status-distribution` |
| Dashboard | GET | `/api/dashboard/download-progress` |
| Dashboard | GET | `/api/dashboard/db-status` |
| Dashboard | GET | `/api/dashboard/file-size-distribution` |
| Dashboard | GET | `/api/dashboard/crawl-timeline` |
| Dashboard | GET | `/api/dashboard/tag-stats` |
| Dashboard | GET | `/api/dashboard/galleries` |
| Dashboard | GET | `/api/dashboard/suggestions` |
| Dashboard | GET | `/api/dashboard/tag-translations` |
| Dashboard | POST | `/api/dashboard/tag-translations/refresh` |
| Dashboard | GET | `/api/dashboard/tag-detail` |
| 去重审核 | GET | `/api/dedupe-reviews` |
| 去重审核 | POST | `/api/dedupe-reviews/{id}/resolve` |
| 去重审核 | POST | `/api/dedupe-reviews/{id}/ai-review` |
| Komga 复核 | GET | `/api/komga-import-reviews` |
| Komga 复核 | POST | `/api/komga-import-reviews/{gid}/retry` |
| 视觉去重 | GET | `/api/visual-dedup/status` |
| 视觉去重 | POST | `/api/visual-dedup/refresh` |
| 视觉去重 | POST | `/api/visual-dedup/refresh/retry` |
| 视觉去重 | POST | `/api/visual-dedup/ai-review` |
| 视觉去重 | GET | `/api/visual-dedup/ai-review/{jobId}` |
| 归档同步 | GET | `/api/archive-sync/status` |
| 归档同步 | POST | `/api/archive-sync/scan` |
| 归档同步 | POST | `/api/archive-sync/cover-match` |
| 归档同步 | GET | `/api/archive-sync/reviews` |
| 归档同步 | POST | `/api/archive-sync/{gid}/synchronize` |
| 归档同步 | POST | `/api/archive-sync/{gid}/redownload` |
| 合集 | GET | `/api/collections` |
| 合集 | POST | `/api/collections` |
| 合集 | PUT | `/api/collections/{id}` |
| 合集 | DELETE | `/api/collections/{id}` |
| 合集 | GET | `/api/collections/{id}/items` |
| 合集 | POST | `/api/collections/{id}/items` |
| 合集 | DELETE | `/api/collections/{id}/items/{gid}` |
| 合集 | GET | `/api/collections/{id}/suggestions` |
| 合集 | GET | `/api/collections/gallery-search` |
| 合集 | GET | `/api/collections/komga-series-sync/status` |
| 合集 | POST | `/api/collections/komga-series-sync` |
| Temporal | GET | `/api/temporal/monitor/workflows` |
| Temporal | GET | `/api/temporal/monitor/workflows/{workflowId}/history` |
| Temporal | POST | `/api/temporal/monitor/workflows/{workflowId}/terminate` |
| AI | POST | `/api/ai/generate-summary` |
| AI | POST | `/api/ai/batch-translate-tags` |
| AI | POST | `/api/ai/dynamic/models` |
| AI | POST | `/api/ai/dynamic/chat` |
| AI | GET | `/api/ai/embedding/status` |
| AI | POST | `/api/ai/embedding` |
| AI 配置 | GET | `/api/ai/config/providers` |
| AI 配置 | POST | `/api/ai/config/providers` |
| AI 配置 | PUT | `/api/ai/config/providers/{id}` |
| AI 配置 | DELETE | `/api/ai/config/providers/{id}` |
| AI 配置 | POST | `/api/ai/config/providers/{id}/models` |
| AI 配置 | GET | `/api/ai/config/use-cases` |
| AI 配置 | PUT | `/api/ai/config/use-cases/{useCase}` |
| AI 配置 | GET | `/api/ai/config/use-cases/{useCase}/default-prompt` |
