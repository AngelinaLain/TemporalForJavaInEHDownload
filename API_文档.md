# TemporalForJavaInEHDow REST API 文档

## 概述

- **Base URL**：`http://127.0.0.1:8001`
- **认证方式**：JWT Bearer Token（`/api/auth/login` 除外）
- **响应格式**：统一 JSON 包装

### 统一响应结构

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": { },
  "timestamp": 1700000000000
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | Integer | 状态码，见下表 |
| `msg` | String | 提示消息 |
| `data` | Any | 业务数据，无数据时为 `null` |
| `timestamp` | Long | 服务器响应时间戳（毫秒） |

### 状态码说明

| code | 含义 |
| --- | --- |
| 200 | 操作成功 |
| 400 | 参数校验失败 |
| 401 | 未登录或 Token 已过期 |
| 403 | 无权限 |
| 500 | 系统繁忙 |

---

## 一、认证

### 登录

`POST /api/auth/login`

不需要认证 Header。

**请求体：**

```json
{
  "username": "admin",
  "password": "your_password"
}
```

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "token": "<jwt-token>",
    "username": "admin"
  },
  "timestamp": 1700000000000
}
```

**失败响应（用户名或密码错误）：**

```json
{
  "code": 401,
  "msg": "用户名或密码错误",
  "data": null,
  "timestamp": 1700000000000
}
```

获取到 token 后，后续所有请求需在 Header 中携带：

```http
Authorization: Bearer <jwt-token>
```

---

## 二、自动化工作流

### 2.1 启动自动化工作流

`POST /api/temporal/eh/start`

按搜索条件抓取 EHentai 画廊并推送到 Synology 下载。

**请求体：**

```json
{
  "keyword": "language:chinese",
  "filterCats": 0,
  "minimumRating": 2,
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

**字段说明：**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `keyword` | String | 是 | — | EHentai 搜索关键词（支持标签语法，如 `language:chinese`） |
| `filterCats` | Integer | 否 | `0` | 分类排除码 `f_cats`，`0` 表示不排除 |
| `minimumRating` | Integer | 否 | `1` | 最低星级过滤 `f_srdd`，`1` 表示不过滤 |
| `language` | String | 否 | — | 语言代码，如 `chinese`、`japanese` |
| `pageAtLeast` | Integer | 否 | — | 最少页数 `f_spf` |
| `pageAtMost` | Integer | 否 | — | 最多页数 `f_spt` |
| `searchExpungedGalleries` | Boolean | 否 | `false` | 是否搜索已删除的画廊 `f_sh` |
| `showOnlyWithTorrents` | Boolean | 否 | `false` | 仅显示有种子的画廊 `f_sto` |
| `disableLanguageFilter` | Boolean | 否 | `false` | 禁用语言过滤 `f_sfl` |
| `disableUploaderFilter` | Boolean | 否 | `false` | 禁用上传者过滤 `f_sfu` |
| `disableTagsFilter` | Boolean | 否 | `false` | 禁用标签过滤 `f_sft` |

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "workflowId": "eh-auto-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "runId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  },
  "timestamp": 1700000000000
}
```

**curl 示例：**

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/start \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt-token>" \
  -d '{
    "keyword": "language:chinese",
    "minimumRating": 3,
    "pageAtLeast": 20
  }'
```

---

### 2.2 重试失败任务

`POST /api/temporal/eh/retry-failed`

对数据库中所有 `download_status = '下载失败'` 的画廊重新触发下载工作流。

**无请求体。**

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "workflowId": "eh-retry-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "runId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  },
  "timestamp": 1700000000000
}
```

**curl 示例：**

```bash
curl -X POST http://127.0.0.1:8001/api/temporal/eh/retry-failed \
  -H "Authorization: Bearer <jwt-token>"
```

---

### 2.3 测试邮件通知

`POST /api/temporal/eh/test-email`

直接调用 Microsoft Graph API 发送测试邮件，用于验证通知配置是否正确。

**请求体（可选）：**

```json
{
  "subject": "EHentai 自动化 - 邮件测试",
  "content": "如果收到此邮件，说明 Microsoft E5 Graph API 凭证配置正确。"
}
```

不传请求体时使用内置默认主题和内容。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "message": "发送邮件指令已执行，请查看控制台日志以及您的管理员邮箱接收情况。"
  },
  "timestamp": 1700000000000
}
```

---

## 三、Komga 管理

### 3.1 构建 Komga 合集

`POST /api/temporal/eh/collections/build-by-tags`

根据指定的 EHentai 标签，从数据库中筛选画廊并在 Komga 中创建或更新对应合集。

**请求体：**

```json
{
  "collectionName": "纯爱 / Vanilla",
  "tags": ["female:sole female", "male:sole male"],
  "matchAllTags": false
}
```

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `collectionName` | String | 是 | — | Komga 合集名称 |
| `tags` | String[] | 是 | — | EHentai 英文标签列表（格式：`namespace:tag`） |
| `matchAllTags` | Boolean | 否 | `false` | `false`：满足任意一个标签即加入；`true`：必须包含所有标签 |

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "合集 [纯爱 / Vanilla] 构建指令已接收并处理成功",
  "timestamp": 1700000000000
}
```

**参数校验失败响应：**

```json
{
  "code": 500,
  "msg": "合集名称和Tags不能为空",
  "data": null,
  "timestamp": 1700000000000
}
```

---

### 3.2 同步标签到 Komga

`POST /api/temporal/eh/sync-tags`

将数据库中存储的 EHentai 标签强制覆盖同步到 Komga 的 Book 和 Series 元数据。异步执行，立即返回。

**无请求体。**

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "同步任务已在后台启动，请查看控制台日志！",
  "timestamp": 1700000000000
}
```

---

### 3.3 批量刷新 Komga 元数据

`POST /api/temporal/eh/batch-refresh-metadata`

对所有已入库书籍触发 Komga 元数据刷新。异步执行，立即返回。

**无请求体。**

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "批量刷新任务已在后台启动！请查看控制台日志获取进度。",
  "timestamp": 1700000000000
}
```

---

### 3.4 批量更新文件大小

`POST /api/temporal/eh/batch-update-filesize`

补全数据库中 `file_size_mb` 为空或为 `0` 的记录。异步执行，立即返回。

**无请求体。**

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "批量更新文件大小任务已在后台启动！请查看控制台日志获取进度。",
  "timestamp": 1700000000000
}
```

---

## 四、Dashboard 统计看板

> 所有 Dashboard 接口均需 JWT 认证。

### 4.1 统计概览

`GET /api/dashboard/stats`

返回画廊总数、各状态数量及总存储大小。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "total": 1500,
    "downloaded": 800,
    "imported": 600,
    "failed": 30,
    "pending": 70,
    "totalSizeGb": 215.43
  },
  "timestamp": 1700000000000
}
```

---

### 4.2 下载状态分布

`GET /api/dashboard/status-distribution`

返回各 `download_status` 的数量，用于饼图展示。只返回数量 > 0 的状态。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    { "name": "已入库", "value": 600 },
    { "name": "已下载", "value": 800 },
    { "name": "下载失败", "value": 30 },
    { "name": "未下载", "value": 70 }
  ],
  "timestamp": 1700000000000
}
```

可能出现的状态值：`未下载`、`下载中`、`已下载`、`下载失败`、`已入库`、`阻断`、`已忽略`

---

### 4.3 文件大小分布

`GET /api/dashboard/file-size-distribution`

返回画廊按文件大小分桶的统计，用于柱状图展示。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "labels": ["<50MB", "50-100MB", "100-200MB", "200-500MB", "500MB-1GB", ">1GB"],
    "data": [120, 340, 580, 350, 80, 30]
  },
  "timestamp": 1700000000000
}
```

---

### 4.4 抓取时间线

`GET /api/dashboard/crawl-timeline`

按日统计画廊抓取数量，返回最近 60 天的数据。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "dates": ["2024-01-01", "2024-01-02", "2024-01-03"],
    "counts": [45, 120, 87]
  },
  "timestamp": 1700000000000
}
```

---

### 4.5 标签命名空间统计

`GET /api/dashboard/tag-stats`

统计各标签命名空间（`female:`、`male:`、`language:` 等）出现次数，返回 Top 20。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    { "name": "female", "nameCn": "女性", "value": 5800 },
    { "name": "male", "nameCn": "男性", "value": 4200 },
    { "name": "language", "nameCn": "语言", "value": 1500 }
  ],
  "timestamp": 1700000000000
}
```

---

### 4.6 画廊列表（分页）

`GET /api/dashboard/galleries`

支持分页、状态筛选、关键词搜索和标签过滤。

**Query 参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `page` | Integer | 否 | `1` | 页码 |
| `size` | Integer | 否 | `20` | 每页数量 |
| `status` | String | 否 | — | 按 `download_status` 过滤，如 `已入库` |
| `keyword` | String | 否 | — | 按 `title` 或 `filename` 模糊搜索 |
| `tag` | String | 否 | — | 按标签精确匹配，如 `female:sole female` |

**成功响应（MyBatis-Plus IPage 结构）：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "records": [
      {
        "gid": 123456,
        "token": "abcdef1234",
        "title": "Gallery Title",
        "filename": "Gallery_Title",
        "galleryUrl": "https://e-hentai.org/g/123456/abcdef1234/",
        "searchQuery": "language:chinese",
        "crawledAt": "2024-01-01T12:00:00.000+00:00",
        "downloadStatus": "已入库",
        "tags": ["language:chinese", "female:sole female"],
        "komgaBookId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        "fileSizeMb": 150.5
      }
    ],
    "total": 1500,
    "size": 20,
    "current": 1,
    "pages": 75
  },
  "timestamp": 1700000000000
}
```

**curl 示例：**

```bash
curl "http://127.0.0.1:8001/api/dashboard/galleries?page=1&size=20&status=已入库&keyword=chinese" \
  -H "Authorization: Bearer <jwt-token>"
```

---

### 4.7 搜索联想

`GET /api/dashboard/suggestions`

根据输入返回匹配的标题和标签建议，同时支持英文原名和中文翻译匹配。

**Query 参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `q` | String | 是 | — | 搜索词 |
| `limit` | Integer | 否 | `10` | 返回数量上限，最大 30 |

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    { "value": "Some Gallery Title", "type": "title" },
    { "value": "female:sole female", "label": "独女 (female:sole female)", "type": "tag" }
  ],
  "timestamp": 1700000000000
}
```

---

### 4.8 标签翻译映射表

`GET /api/dashboard/tag-translations`

返回完整的标签翻译映射表（英文 `namespace:tag` → 中文名）。

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "female:sole female": "独女",
    "male:sole male": "独男",
    "language:chinese": "中文"
  },
  "timestamp": 1700000000000
}
```

---

### 4.9 刷新翻译缓存

`POST /api/dashboard/tag-translations/refresh`

手动触发 EhTag 翻译缓存刷新。

**无请求体。**

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "翻译缓存刷新成功",
  "timestamp": 1700000000000
}
```

---

### 4.10 标签详情

`GET /api/dashboard/tag-detail`

获取单个标签的中文名和描述。

**Query 参数：**

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `tag` | String | 是 | 标签，格式 `namespace:tag`，如 `female:sole female` |

**成功响应：**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "tag": "female:sole female",
    "name": "独女",
    "intro": "作品中只有一名女性角色。"
  },
  "timestamp": 1700000000000
}
```

---

## 五、接口汇总

| 功能 | 方法 | 路径 | 认证 |
| --- | --- | --- | --- |
| 登录获取 Token | POST | `/api/auth/login` | 无 |
| 启动自动化工作流 | POST | `/api/temporal/eh/start` | JWT |
| 重试失败任务 | POST | `/api/temporal/eh/retry-failed` | JWT |
| 测试邮件通知 | POST | `/api/temporal/eh/test-email` | JWT |
| 构建 Komga 合集 | POST | `/api/temporal/eh/collections/build-by-tags` | JWT |
| 同步标签到 Komga | POST | `/api/temporal/eh/sync-tags` | JWT |
| 批量刷新 Komga 元数据 | POST | `/api/temporal/eh/batch-refresh-metadata` | JWT |
| 批量更新文件大小 | POST | `/api/temporal/eh/batch-update-filesize` | JWT |
| 统计概览 | GET | `/api/dashboard/stats` | JWT |
| 下载状态分布 | GET | `/api/dashboard/status-distribution` | JWT |
| 文件大小分布 | GET | `/api/dashboard/file-size-distribution` | JWT |
| 抓取时间线 | GET | `/api/dashboard/crawl-timeline` | JWT |
| 标签命名空间统计 | GET | `/api/dashboard/tag-stats` | JWT |
| 画廊列表（分页） | GET | `/api/dashboard/galleries` | JWT |
| 搜索联想 | GET | `/api/dashboard/suggestions` | JWT |
| 标签翻译映射表 | GET | `/api/dashboard/tag-translations` | JWT |
| 刷新翻译缓存 | POST | `/api/dashboard/tag-translations/refresh` | JWT |
| 标签详情 | GET | `/api/dashboard/tag-detail` | JWT |
