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

只有 `keyword` 必填；其余字段可省略并采用示例中的默认值，页数上下限默认为空。成功时返回 `workflowId` 和 `runId`。

### `POST /api/temporal/eh/retry-failed`

异步重试数据库中的下载失败任务。无请求体，返回 `workflowId` 和 `runId`。

### `POST /api/temporal/eh/test-email`

发送测试邮件。请求体可省略，也可传：

```json
{ "subject": "邮件测试", "content": "测试内容" }
```

### `POST /api/temporal/eh/collections/build-by-tags`

按标签创建或更新 Komga 合集。该端点是旧的“按标签直接构建”能力；自定义合集管理请使用第 9 节接口。

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
| GET | `/api/dashboard/suggestions` | `q` 必填；`limit=10`，最大 30；`type=all|title|tag` |
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

已有刷新任务运行时返回业务码 `409`；重试列表非法时返回 `400`。

## 8. 群晖归档同步

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

## 9. 自定义画廊合集

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
| GET | `/api/collections/gallery-search` | `keyword` 可选，`scope=unassigned|all`，`limit=30` |

成员/候选字段包括 `gid`、`title`、`originalTitle`、`galleryUrl`、`pageCount`、`rating`、合集信息，以及建议场景下的 `score`、`titleSimilarity`、`coverSimilarity`、`metadataSimilarity`、`reason`。

### Komga Series 同步

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/collections/komga-series-sync/status` | 查询最近或当前同步任务状态 |
| POST | `/api/collections/komga-series-sync` | 启动合集到 Komga Series 的同步 |

同步任务已运行时返回业务码 `409`。

## 10. Temporal 监控

### `GET /api/temporal/monitor/workflows`

读取最近最多 1000 个工作流并按父子关系返回树。节点包含 `workflowId`、`runId`、`type`、`status`、`startTime`、`closeTime`、`historyLength`、`children`。

### `GET /api/temporal/monitor/workflows/{workflowId}/history`

可选 Query：`runId`。返回按事件顺序排列的可读日志：`eventId`、Unix 秒时间戳 `time`、`type`、`level`、`message`。

### `POST /api/temporal/monitor/workflows/{workflowId}/terminate`

可选 Query：`runId`、`reason`。默认原因为“用户在监控页面手动终止”。该操作不可撤销。

## 11. AI 服务内部接口

AI 服务端点不使用主服务的 `Result` 包装，而是直接返回 HTTP 状态和响应体。当前未在管理前端直接调用。

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

## 12. Actuator

| 路径 | 认证 | 说明 |
| --- | --- | --- |
| `/actuator/health` | 无 | 健康检查 |
| `/actuator/info` | 无 | 应用信息 |
| `/actuator/prometheus` | 无 | Prometheus 指标 |
| `/actuator/**` 其他端点 | 管理员 JWT | Spring Boot 运维端点 |

## 13. 完整端点索引

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
| Komga 复核 | GET | `/api/komga-import-reviews` |
| Komga 复核 | POST | `/api/komga-import-reviews/{gid}/retry` |
| 视觉去重 | GET | `/api/visual-dedup/status` |
| 视觉去重 | POST | `/api/visual-dedup/refresh` |
| 视觉去重 | POST | `/api/visual-dedup/refresh/retry` |
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
