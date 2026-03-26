# EHentai 自动化爬虫 — REST API 文档

## 📌 端点：POST /api/temporal/eh/start

### 基本信息

| 项目 | 值 |
|------|-----|
| **Method** | `POST` |
| **URL** | `http://127.0.0.1:8001/api/temporal/eh/start` |
| **Content-Type** | `application/json` |
| **Authentication** | 内置 Cookie（无需额外认证） |

---

## 📥 请求体参数

### JSON Schema

```json
{
  "keyword": "string (required, non-empty)",
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

### 参数说明

| 参数 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| **`keyword`** | String | ✅ | — | **搜索关键词**，不能为空。支持 EHentai 高级搜索语法 |
| **`fCats`** | Integer | ❌ | `0` | **分类排除码**（位运算组合），`0` = 不排除任何分类 |
| **`minimumRating`** | Integer | ❌ | `1` | **最低星级过滤**，范围 1-5。`1` = 不过滤，`2-5` = 仅保留该星级及以上的作品 |
| **`language`** | String | ❌ | `null` | **语言过滤**，如 `"chinese"`, `"japanese"`, `"korean"` 等。`null` 表示不过滤 |
| **`pageAtLeast`** | Integer | ❌ | `null` | **最少页数** (f_spf)，仅搜索至少有指定页数的作品 |
| **`pageAtMost`** | Integer | ❌ | `null` | **最多页数** (f_spt)，仅搜索不超过指定页数的作品 |
| **`searchExpungedGalleries`** | Boolean | ❌ | `false` | **搜索已删除的画廊** (f_sh)，`true` 包含被删除的作品 |
| **`showOnlyWithTorrents`** | Boolean | ❌ | `false` | **仅显示有种子的** (f_sto)，`true` 仅返回有种子的作品 |
| **`disableLanguageFilter`** | Boolean | ❌ | `false` | **禁用语言过滤** (f_sfl)，`true` 不使用语言 tag 进行过滤 |
| **`disableUploaderFilter`** | Boolean | ❌ | `false` | **禁用上传者过滤** (f_sfu)，`true` 不过滤特定上传者 |
| **`disableTagsFilter`** | Boolean | ❌ | `false` | **禁用标签过滤** (f_sft)，`true` 忽略 tag 相关的搜索限制 |

### keyword 搜索语法示例

```javascript
// 单条件
"language:chinese"                    // 仅中文作品
"language:japanese"                   // 仅日文作品
"female:corruption"                   // 包含"腐化"女性标签
"male:gender change"                  // 包含"性别变化"男性标签

// 多条件组合（空格分隔）
"language:chinese female:corruption"

// 复杂组合
"language:chinese female:corruption male:\"gender change$\""
```

### fCats 分类排除码

#### 分类定义

| 分类 | 排除码 | 说明 |
|------|--------|------|
| Misc | `1` | 其他 |
| Doujinshi | `2` | 同人志 |
| Manga | `4` | 漫画 |
| Artist CG | `8` | 艺术 CG |
| Game CG | `16` | 游戏 CG |
| Image Set | `32` | 图像集 |
| Cosplay | `64` | Cosplay |
| Asian Porn | `128` | 亚洲成人视频 |
| Non-H | `256` | 无码作品 |
| Western | `512` | 西方作品 |

#### 常用组合例子

```javascript
fCats = 0      // 不排除任何分类（默认，全选）
fCats = 2      // 仅排除 Doujinshi
fCats = 514    // 排除 Doujinshi(2) + Western(512)
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
  "code": 0,
  "message": "success",
  "data": {
    "workflowId": "eh-auto-550e8400-e29b-41d4-a716-446655440000",
    "runId": "2a6c3ec1-f123-4567-8901-a2b3c4d5e6f7"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | Integer | `0` = 成功 |
| `message` | String | `"success"` |
| `data.workflowId` | String | Temporal Workflow ID（格式：`eh-auto-{UUID}`） |
| `data.runId` | String | Temporal Run ID（UUID） |

### 错误响应

**参数验证错误（HTTP 400）**
```json
{
  "code": 400,
  "message": "keyword 不能为空"
}
```

**服务器错误（HTTP 500）**
```json
{
  "code": 500,
  "message": "Workflow 启动失败: ..."
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
| 2.0 | 2026-03-26 | **性能升级与功能大幅扩展**：引入 SearchOptions 统一对象模型（对标 JHenTai），支持 8 项新搜索过滤功能。新增：语言过滤（language）、页数范围过滤（pageAtLeast/pageAtMost）、已删除画廊搜索（searchExpungedGalleries）、仅种子过滤（showOnlyWithTorrents）、过滤器禁用等高级选项。解决 Temporal 6 参数限制问题，精化 URL 参数构建逻辑 |
| 1.1 | 2026-03-26 | 新增星级过滤功能（minimumRating），对标 JHenTai 实现，支持 1-5 星过滤 |
| 1.0 | 2026-03-26 | 初始版本，包含完整参数说明和示例 |
