# EHentai 自动化爬虫 — REST API 文档

该文档描述了本 Temporal 工作流服务的全部 REST API 接口、参数说明、请求/响应示例及错误处理机制。

---

## 📋 快速导航

| 接口名 | 方法 | 路径 | 说明 |
|-------|------|------|------|
| [启动自动化工作流](#启动自动化工作流) | `POST` | `/api/temporal/eh/start` | 按关键词搜索画廊并启动下载流程 |
| [重试失败任务](#重试失败任务) | `POST` | `/api/temporal/eh/retry-failed` | 重试数据库中所有失败的画廊下载 |
| [测试邮件配置](#测试邮件配置) | `POST` | `/api/temporal/eh/test-email` | 验证 Microsoft Graph API 邮件通知配置是否正确 |

---

## 📌 启动自动化工作流

### 基本信息

| 项目 | 值 |
|------|-----|
| **方法** | `POST` |
| **URL** | `http://127.0.0.1:8001/api/temporal/eh/start` |
| **Content-Type** | `application/json` |
| **认证** | 无（内置 Cookie 认证） |
| **异步** | ✅ 异步执行（返回 workflowId 和 runId） |

---

### 📥 请求体参数

#### JSON Schema

```json
{
  "keyword": "string (required, 非空)",
  "fCats": "integer (optional, default: 0)",
  "minimumRating": "integer (optional, default: 1)",
  "language": "string (optional, default: null)",
  "pageAtLeast": "integer (optional, default: null)",
  "pageAtMost": "integer (optional, default: null)",
  "searchExpungedGalleries": "boolean (optional, default: false)",
  "showOnlyWithTorrents": "boolean (optional, default: false)",
  "disableLanguageFilter": "boolean (optional, default: false)",
  "disableUploaderFilter": "boolean (optional, default: false)",
  "disableTagsFilter": "boolean (optional, default: false)"
}
```

#### 参数详解

| 参数 | 类型 | 必需 | 默认 | 范围/说明 |
|------|------|------|------|----------|
| **`keyword`** | String | ✅ | — | 搜索关键词，不能为空。支持 EHentai 高级搜索语法（见下表） |
| **`fCats`** | Integer | ❌ | `0` | 分类排除码（位运算组合）。`0` = 不排除，其他值见"分类码"表 |
| **`minimumRating`** | Integer | ❌ | `1` | 最低星级（1-5）：`1` = 不过滤，`2-5` = 该星级以上 |
| **`language`** | String | ❌ | `null` | 语言代码：`"chinese"`, `"japanese"`, `"korean"$ 等，`null` = 不过滤 |
| **`pageAtLeast`** | Integer | ❌ | `null` | 最少页数：仅返回至少有这么多页的作品 |
| **`pageAtMost`** | Integer | ❌ | `null` | 最多页数：仅返回不超过这么多页的作品 |
| **`searchExpungedGalleries`** | Boolean | ❌ | `false` | 搜索已删除画廊：`true` = 包含被从网站移除的作品 |
| **`showOnlyWithTorrents`** | Boolean | ❌ | `false` | 仅种子作品：`true` = 仅返回已发布种子的作品 |
| **`disableLanguageFilter`** | Boolean | ❌ | `false` | 禁用语言 tag 过滤：`true` = 忽略语言相关限制 |
| **`disableUploaderFilter`** | Boolean | ❌ | `false` | 禁用上传者过滤：`true` = 忽略上传者相关限制 |
| **`disableTagsFilter`** | Boolean | ❌ | `false` | 禁用标签过滤：`true` = 忽略所有 tag 相关限制 |

### 搜索语法示例

```javascript
// 单条件
"language:chinese"                    // 仅中文作品
"language:japanese"                   // 仅日文作品
"female:corruption"                   // 包含"腐化"女性标签
"male:gender change"                  // 包含"性别变化"男性标签

// 多条件组合（空格分隔）
"language:chinese female:corruption"

// 复杂组合（带引号的精确匹配）
"language:chinese female:corruption male:\"gender change$\""

// 排除语法（使用 - 前缀）
"female:corruption -language:japanese"  // 包含腐化但排除日文

// 标签名精确匹配示例
"artist:\"example artist$\""
```

### 分类排除码表

| 分类代码 | 含义 | 排除码 |
|---------|------|--------|
| Misc | 其他 | `1` |
| Doujinshi | 同人志 | `2` |
| Manga | 漫画 | `4` |
| Artist CG | 艺术 CG | `8` |
| Game CG | 游戏 CG | `16` |
| Image Set | 图像集 | `32` |
| Cosplay | Cosplay | `64` |
| Asian Porn | 亚洲成人视频 | `128` |
| Non-H | 无码作品 | `256` |
| Western | 西方作品 | `512` |

**fCats 组合示例**：

- `fCats = 0` — 不排除任何分类（默认，全选）
- `fCats = 2` — 仅排除 Doujinshi
- `fCats = 514` — 排除 Doujinshi(2) + Western(512)
- `fCats = 3` — 排除 Misc(1) + Doujinshi(2)

### 📤 响应体

#### 成功响应 (200 OK)

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "workflowId": "eh-auto-f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "runId": "c7a8b52f-9c8b-4c8d-a1d0-2e8f7a9c4e1b"
  }
}
```

**字段说明**：

- `code`: `"SUCCESS"` — 请求成功
- `message`: 成功提示信息
- `data.workflowId`: Temporal 工作流 ID，可用于查询工作流状态
- `data.runId`: 工作流运行 ID

#### 参数验证失败 (400 Bad Request)

```json
{
  "code": "BAD_REQUEST",
  "message": "keyword 不能为空",
  "data": null
}
```

---

## 📌 重试失败任务

### 基本信息

| 项目 | 值 |
|------|-----|
| **方法** | `POST` |
| **URL** | `http://127.0.0.1:8001/api/temporal/eh/retry-failed` |
| **Content-Type** | `application/json` |
| **认证** | 无 |
| **异步** | ✅ 异步执行 |
| **请求体** | 空（无参数） |

### 工作原理

1. 扫描数据库中所有 `download_status` 为 `FAILED` 或 `DOWNLOADED_PENDING` 的画廊
2. 为每条失败记录启动一个独立的 `SingleGalleryDownloadWorkflow` 子工作流
3. 子工作流实现单画廊的完整下载 → 入库 → 通知流程
4. 所有子工作流并行执行（工作量由 Temporal Worker 线程池管理）

### 📥 请求示例

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/retry-failed \
  -H "Content-Type: application/json"
```

### 📤 响应体

#### 成功响应 (200 OK)

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "workflowId": "eh-retry-a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "runId": "9876f5e4-d3c2-b1a0-9f8e-7d6c5b4a3210"
  }
}
```

---

## 📌 测试邮件配置

### 基本信息

| 项目 | 值 |
|------|-----|
| **方法** | `POST` |
| **URL** | `http://127.0.0.1:8001/api/temporal/eh/test-email` |
| **Content-Type** | `application/json` |
| **认证** | 无 |
| **异步** | ❌ 同步执行（等待完成后返回） |

### 功能说明

直接调用 NotificationActivity 的发邮件接口（绕过 Temporal 框架），用于快速验证 Microsoft Graph API 凭证是否正确配置。

### 📥 请求体

#### 最小化请求（使用默认邮件内容）

```json
{}
```

或

```json
null
```

#### 自定义邮件内容（可选）

```json
{
  "subject": "自定义测试主题",
  "content": "这是自定义的邮件内容。"
}
```

**字段说明**：

- `subject`: 邮件主题（可选，默认："EHentai 自动化 - 邮件测试"）
- `content`: 邮件正文（可选，默认："这是一封测试邮件..."）

### 📥 请求示例

```bash
# 方式 1：使用默认内容
curl -X POST http://127.0.0.1:8001/api/temporal/eh/test-email \
  -H "Content-Type: application/json" \
  -d '{}'

# 方式 2：自定义邮件内容
curl -X POST http://127.0.0.1:8001/api/temporal/eh/test-email \
  -H "Content-Type: application/json" \
  -d '{
    "subject": "我的邮件测试",
    "content": "邮件配置是否正确？如果收到此邮件，则正确！"
  }'
```

### 📤 响应体

#### 成功响应 (200 OK)

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "message": "发送邮件指令已执行，请查看控制台日志以及您的管理员邮箱接收情况。"
  }
}
```

#### 失败响应示例

如果 Graph API 配置错误（如凭证无效）：

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "发送邮件失败: Client error: 401 Unauthorized",
  "data": null
}
```

**排查步骤**：

1. 检查 `application.yaml` 中 `eh-config.notification` 的配置
2. 验证 Microsoft Entra ID 应用程序凭证（tenant_id, client_id, client_secret）
3. 确认应用程序有 `Mail.Send` 权限
4. 查看应用日志获取详细错误信息

---

## 🔄 工作流执行流程与查询

### 工作流执行生命周期

```
顶级工作流启动
  │
  ├─ [Scraper Activity] 搜索并爬取画廊列表
  │
  ├─ [Database Activity] 批量保存/更新到 MySQL
  │
  └─ [For Each Gallery] 逐个处理
      ├─ [Synology Activity] 推送下载链接
      ├─ [Database Activity] 更新下载状态为 DOWNLOADING
      ├─ [Loop Poll Activity] 轮询下载状态（最多 20 次，每次间隔 15 秒）
      │   └─ 当下载完成时，继续
      ├─ [Komga Activity] 触发媒体库入库 (3 个子步骤)
      │   ├─ 触发文件重命名
      │   ├─ 抽取元数据
      │   └─ 扫描媒体库
      └─ [Notification Activity] 发送下载完成邮件
```

### 通过 Temporal Web UI 查询工作流

Temporal 默认提供 Web UI 来查询、监控、调试工作流。

**访问地址**：`http://127.0.0.1:8233`

**常见查询操作**：

- 输入 `workflowId` 查询单个工作流的执行进度
- 查看工作流执行历史与事件时间线
- 查看每个 Activity 的执行日志与错误堆栈
- 重放（重新执行）失败的工作流

---

## ❌ 错误处理与错误码

### 错误响应格式

所有错误响应遵循统一格式：

```json
{
  "code": "ERROR_CODE",
  "message": "人类可读的错误信息",
  "data": null
}
```

### 错误码列表

| 错误码 | HTTP 状态 | 说明 | 对应场景 |
|--------|----------|------|---------|
| `SUCCESS` | 200 | 请求成功 | 正常完成 |
| `BAD_REQUEST` | 400 | 请求参数错误 | keyword 为空、参数类型错误等 |
| `INTERNAL_SERVER_ERROR` | 500 | 服务器内部错误 | Temporal 连接失败、数据库异常等 |
| `VALIDATION_ERROR` | 400 | 参数验证失败 | 参数值不在范围内等 |

### Activity 业务错误码

在工作流执行过程中，各 Activity 可能抛出业务异常。这些错误被记录在数据库字段 `error_type` 中：

| 错误类型 | 代码 | 重试 | 说明 |
|---------|------|------|------|
| **配额超限** | `QUOTA_EXCEEDED` | ❌ 不重试 | EHentai 请求过于频繁，被限流 |
| **IP 被封** | `IP_BANNED` | ❌ 不重试 | IP 已被 EHentai 列入黑名单 |
| **链接提取失败** | `ARCHIVE_LINK_EXTRACT_FAILED` | ❌ 不重试 | 无法提取下载链接（可能是权限问题） |
| **群晖认证失败** | `SYNOLOGY_AUTH_FAILED` | ❌ 不重试 | Synology 凭据错误或服务离线 |
| **网络超时** | `NETWORK_TIMEOUT` | ✅ 重试 | 网络请求超时，自动重试最多 3 次 |
| **临时故障** | `TEMPORARY_FAILURE` | ✅ 重试 | 临时网络/服务故障，重试 |
| **未知错误** | `UNKNOWN_ERROR` | ✅ 重试 | 未分类的错误 |

### 数据库中的错误记录

当 Activity 执行失败时，相关字段会被更新：

```sql
UPDATE eh_galleries 
SET 
  download_status = 'FAILED',
  error_type = 'IP_BANNED',
  error_message = '您的 IP 已被 EHentai 列入黑名单'
WHERE gid = 12345;
```

---

## 📚 完整 API 请求示例

### 示例 1：搜索中文作品并下载

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/start \
  -H "Content-Type: application/json" \
  -d '{
    "keyword": "language:chinese",
    "minimumRating": 2,
    "pageAtLeast": 10
  }'
```

**预期响应**：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "workflowId": "eh-auto-12345678-abcd-ef01-2345-6789abcdef01",
    "runId": "00000000-0000-0000-0000-000000000001"
  }
}
```

### 示例 2：搜索有种子的日文作品

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/start \
  -H "Content-Type: application/json" \
  -d '{
    "keyword": "language:japanese",
    "showOnlyWithTorrents": true,
    "fCats": 256
  }'
```

### 示例 3：重试所有失败任务

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/retry-failed \
  -H "Content-Type: application/json"
```

### 示例 4：发送测试邮件

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/test-email \
  -H "Content-Type: application/json" \
  -d '{
    "subject": "Graph API 配置正确性验证",
    "content": "如果成功收到此邮件，说明您的 Microsoft Graph API 凭证配置完全正确！"
  }'
```

---

## 🔗 相关文档

- [main README.md](README.md) — 项目概览、架构、启动方式
- [Temporal 官方 Java SDK](https://docs.temporal.io/develop/java/)
- [EHentai 高级搜索语法](https://ehwiki.org/wiki/Advanced_Search)
- [Microsoft Graph API 邮件文档](https://learn.microsoft.com/en-us/graph/api/user-sendmail)
fCats = 6      // 排除 Doujinshi(2) + Manga(4)  
fCats = 510    // 排除 Doujinshi + Manga + Artist CG + Game CG + Image Set + Cosplay + Asian Porn + Non-H（仅保留 Misc + Western）
fCats = 1023   // 全部排除（无意义，结果为空）
```

### minimumRating 星级过滤（对标 JHenTai）

**参数定义**（对应 EHentai `f_srdd` 参数）

| 值 | 说明 | API 行为 |
|-----|------|---------|
| `1` | 不过滤（默认） | 不添加 `f_srdd` 参数 |
| `2` | 仅 2 星及以上 | 添加 `f_srdd=2` |
| `3` | 仅 3 星及以上 | 添加 `f_srdd=3` |
| `4` | 仅 4 星及以上 | 添加 `f_srdd=4` |
| `5` | 仅 5 星（精品） | 添加 `f_srdd=5` |

**常用场景**

```javascript
minimumRating = 1    // 默认，无星级限制（全部作品）
minimumRating = 3    // 仅 3 星及以上（评价较好）
minimumRating = 4    // 仅 4-5 星（高质量）
minimumRating = 5    // 仅 5 星精品（最严格）
```

### 成功响应（HTTP 200）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "workflowId": "eh-auto-550e8400-e29b-41d4-a716-446655440000",
    "runId": "2a6c3ec1-f123-4567-8901-a2b3c4d5e6f7"
  },
  "timestamp": 1743302400000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | Integer | `200` = 成功 |
| `msg` | String | `"操作成功"` |
| `data.workflowId` | String | Temporal Workflow ID（格式：`eh-auto-{UUID}`） |
| `data.runId` | String | Temporal Run ID（UUID） |
| `timestamp` | Long | 响应时间戳（毫秒） |

### 错误响应

**参数验证错误（HTTP 400）**
```json
{
  "code": 400,
  "msg": "keyword 不能为空",
  "data": null,
  "timestamp": 1743302400000
}
```

**服务器错误（HTTP 500）**
```json
{
  "code": 500,
  "msg": "Workflow 启动失败: ...",
  "data": null,
  "timestamp": 1743302400000
}
```

---

## 🧪 请求示例

### 最小化示例
```json
{
  "keyword": "language:chinese"
}
```

### 含星级过滤示例
```json
{
  "keyword": "language:chinese",
  "minimumRating": 4
}
```

### 含语言过滤示例
```json
{
  "keyword": "female:corruption",
  "language": "chinese"
}
```

### 含页数范围过滤示例
```json
{
  "keyword": "language:chinese",
  "pageAtLeast": 20,
  "pageAtMost": 100
}
```

### 含高级选项示例
```json
{
  "keyword": "language:chinese female:corruption",
  "fCats": 514,
  "minimumRating": 4,
  "language": "chinese",
  "pageAtLeast": 10,
  "pageAtMost": 50,
  "searchExpungedGalleries": false,
  "showOnlyWithTorrents": true,
  "disableLanguageFilter": false,
  "disableUploaderFilter": false,
  "disableTagsFilter": false
}
```

### cURL 测试
```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/start \
  -H "Content-Type: application/json" \
  -d '{
    "keyword": "language:chinese female:corruption",
    "fCats": 514,
    "minimumRating": 4,
    "language": "chinese",
    "pageAtLeast": 10,
    "pageAtMost": 50,
    "showOnlyWithTorrents": true
  }'
```

### PowerShell 测试
```powershell
$body = @{
    keyword = "language:chinese female:corruption"
    fCats = 514
    minimumRating = 4
    language = "chinese"
    pageAtLeast = 10
    pageAtMost = 50
    searchExpungedGalleries = $false
    showOnlyWithTorrents = $true
    disableLanguageFilter = $false
    disableUploaderFilter = $false
    disableTagsFilter = $false
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/temporal/eh/start" `
  -Method Post `
  -Body $body `
  -ContentType "application/json" | ConvertTo-Json
```

---

---

## 📌 端点：POST /api/temporal/eh/retry-failed

### 基本信息

| 项目 | 值 |
|------|-----|
| **Method** | `POST` |
| **URL** | `http://127.0.0.1:8001/api/temporal/eh/retry-failed` |
| **Content-Type** | 无（无请求体） |
| **Authentication** | 内置 Cookie（无需额外认证） |

### 功能说明

扫描数据库中**所有**下载状态为 `下载失败` 的画廊记录，并为每一条记录重新发起下载任务。此外，也会对状态为 `已下载` 但尚未被 Komga 识别/标记元数据的画廊执行**断点补偿**。

**无请求体**，直接 POST 即可触发。

### 重试逻辑说明

| 状态 | 处理方式 |
|------|----------|
| `下载失败` | 重置为「下载中」→ 重新从 EHentai 获取直链 → 重新推送至 Synology → 轮询完成后更新状态 |
| `已下载`（Komga 未入库） | 重新获取元数据 → 重命名文件 → 触发 Komga 扫描 → 轮询 Komga 入库（最多 20 次，间隔 15 秒） |

### 成功响应（HTTP 200）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "workflowId": "eh-retry-550e8400-e29b-41d4-a716-446655440000",
    "runId": "9f3a1bc2-d456-7890-b123-c4d5e6f7a8b9"
  },
  "timestamp": 1743302400000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | Integer | `200` = 成功 |
| `msg` | String | `"操作成功"` |
| `data.workflowId` | String | Temporal Workflow ID（格式：`eh-retry-{UUID}`） |
| `data.runId` | String | Temporal Run ID（UUID） |
| `timestamp` | Long | 响应时间戳（毫秒） |

> **注意**：响应成功仅代表工作流已被**提交**，实际重试结果需通过 Temporal UI 或数据库查询确认。若数据库中不存在任何失败记录，工作流会立刻正常结束，不会报错。

### 测试示例

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/retry-failed
```

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/temporal/eh/retry-failed" -Method Post | ConvertTo-Json
```

---

## 📌 端点：POST /api/temporal/eh/test-email

### 基本信息

| 项目 | 值 |
|------|-----|
| **Method** | `POST` |
| **URL** | `http://127.0.0.1:8001/api/temporal/eh/test-email` |
| **Content-Type** | `application/json`（可选） |
| **Authentication** | 内置 Microsoft Graph API 凭证 |

### 功能说明

**调试/测试专用**。直接调用 `EHAutomationActivity.sendEmailAlert()`（**绕过 Temporal 工作流引擎**，同步执行），用于验证 Microsoft E5 Graph API 凭证是否配置正确。

### 请求体参数（可选）

不传请求体时使用内置默认文案；传入 JSON 可自定义主题和正文：

```json
{
  "subject": "自定义邮件主题（可选）",
  "content": "自定义邮件正文（可选）"
}
```

| 参数 | 类型 | 必需 | 默认值 | 说明 |
|------|------|------|--------|------|
| `subject` | String | ❌ | `"EHentai 自动化 - 邮件测试"` | 邮件主题 |
| `content` | String | ❌ | `"这是一封测试邮件..."` | 邮件正文 |

### 成功响应（HTTP 200）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "message": "发送邮件指令已执行，请查看控制台日志以及您的管理员邮箱接收情况。"
  },
  "timestamp": 1743302400000
}
```

> **注意**：HTTP 200 仅表示调用指令已执行。是否实际收到邮件需查看应用日志和管理员邮箱，若 Graph API 凭证配置有误，日志中会打印详细错误信息。

### 测试示例

**使用默认文案**
```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/test-email
```

**使用自定义文案**
```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/test-email \
  -H "Content-Type: application/json" \
  -d '{"subject": "连通性测试", "content": "Graph API 工作正常！"}'
```

```powershell
$body = @{ subject = "连通性测试"; content = "Graph API 工作正常！" } | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/temporal/eh/test-email" `
  -Method Post -Body $body -ContentType "application/json" | ConvertTo-Json
```

---

## 🔄 工作流执行流程

请求成功后，后台启动异步工作流（所有活动自动执行，不需手动干预）：

```
1️⃣ scrapeGalleries
   ├─ 使用 keyword + fCats 向 EHentai 发送搜索请求
   ├─ 自动翻页（最多 10 页）
   └─ 按 GID 去重，返回画廊列表

2️⃣ saveToDatabase
   └─ 将爬取的画廊持久化到 MySQL 的 eh_galleries 表

3️⃣ extractDownloadUrl
   ├─ POST 到 EHentai 的 archiver.php
   ├─ 请求压缩包生成
   └─ 返回下载链接

4️⃣ pushToSynology
   ├─ 连接到 Synology DownloadStation (10.10.10.40:5001)
   └─ 创建下载任务

5️⃣ checkSynologyTaskStatus (轮询)
   ├─ 每 5 分钟检查一次任务状态
   └─ 最多轮询 24 小时

6️⃣ updateGalleryStatus
   └─ 将处理状态写回数据库（未下载/下载中/已下载/失败等）

7️⃣ sendEmailAlert (可选)
   └─ 下载完成或失败时发送邮件通知
```

### 状态标签

数据库中的状态字段可能值：
- `未下载` — 初始状态
- `下载中` — Synology 任务运行中
- `已下载` — 任务完成
- `下载失败` — 任务失败
- `阻断` — IP 被阻止或其他致命错误

---

## 💡 实用建议

### 1. 监控工作流进度

- 保存响应中的 `workflowId`
- 访问 Temporal UI：`http://temporal-server:8080`（如已部署）
- 或查询数据库表：`eh_galleries` 和 `eh_automation_status`

### 2. 搜索关键词最佳实践

- 使用 EHentai 站点的高级搜索测试语法
- 多关键词用空格分隔（如 `language:chinese female:corruption`）
- 避免拼写错误，否则可能返回 0 结果

### 3. 分类过滤策略

```javascript
fCats = 0      // 默认，不排除任何分类（全选）
fCats = 1021   // 仅要同人志（排除其他所有）
fCats = 1019   // 仅要漫画
fCats = 512    // 排除 Western
fCats = 256    // 排除 Non-H
fCats = 514    // 排除 Doujinshi + Western
```

### 4. 星级过滤策略（新功能）

```javascript
minimumRating = 1    // 默认，无星级限制（所有评分）
minimumRating = 3    // 评价较好的作品（3～5 星）
minimumRating = 4    // 高质量作品（仅 4-5 星）
minimumRating = 5    // 精品作品（仅 5 星，最严格）
```

**对标 JHenTai**：实现与 Flutter 版本保持一致，API 参数为 `f_srdd`，仅当 `minimumRating > 1` 时添加到搜索 URL。

### 5. 数据库查询

**检查爬虫结果**
```sql
SELECT * FROM eh_galleries 
WHERE search_keyword LIKE '%keyword%' 
ORDER BY gid DESC;
```

**检查下载状态**
```sql
SELECT gid, title, status FROM eh_galleries 
WHERE status != '已下载' 
LIMIT 20;
```

---

## ⚙️ 系统配置信息

| 配置项 | 值 |
|--------|-----|
| 应用端口 | `8001` |
| Temporal 服务器 | `10.10.10.161:7233` |
| Synology 地址 | `10.10.10.40:5001` |
| 代理地址 | `10.10.10.32:7893` (Clash) |
| EHentai Cookie | 预配置 4 字段（memberid, passhash, sk, star） |
| 最大爬虫页数 | 10 页 |
| 下载状态轮询周期 | 5 分钟 |
| 最大轮询时间 | 24 小时 |

---

## 📋 更新日志

| 版本 | 日期 | 说明 |
|------|------|------|
| 2.1 | 2026-03-30 | **文档完善**：补充 `retry-failed` 和 `test-email` 两个端点的完整说明；修正 `Result` 响应字段（`code: 200`, `msg: "操作成功"`）；新增端点总览表 |
| 2.0 | 2026-03-26 | **性能升级与功能大幅扩展**：引入 SearchOptions 统一对象模型（对标 JHenTai），支持 8 项新搜索过滤功能。新增：语言过滤（language）、页数范围过滤（pageAtLeast/pageAtMost）、已删除画廊搜索（searchExpungedGalleries）、仅种子过滤（showOnlyWithTorrents）、过滤器禁用等高级选项。解决 Temporal 6 参数限制问题，精化 URL 参数构建逻辑 |
| 1.1 | 2026-03-26 | 新增星级过滤功能（minimumRating），对标 JHenTai 实现，支持 1-5 星过滤 |
| 1.0 | 2026-03-26 | 初始版本，包含完整参数说明和示例 |
