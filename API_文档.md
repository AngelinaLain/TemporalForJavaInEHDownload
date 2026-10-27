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

主服务的 JSON 业务接口使用统一响应。除 Spring Security 在认证/授权失败时直接返回 HTTP `401/403` 外，控制器中的业务错误通常仍是 HTTP `200`，调用方应以响应体 `code` 判断成功与否。第 4 节的封面和图片接口成功时直接返回图片二进制，不套此 JSON；媒体预览的参数或图片源错误使用实际 HTTP 状态码。

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

一般列表分页接口会把 `page` 修正为至少 `1`，把 `size` 限制在 `1–100`。媒体图片分页单独要求 `page >= 1`，`size` 只能为 `10/20/50/100`。

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

账号尚未在系统内修改过密码时，使用 `ADMIN_PASSWORD_HASH` 作为初始密码；修改后的 BCrypt 哈希保存在 V22 创建的 `eh_admin_credentials` 表中，重启不会回退到环境变量中的旧密码。签发的 JWT 包含 `credentialVersion`，受保护请求通过用户名主键查询当前版本；改密提交后，所有旧版本会话立即失效，不受 Redis 黑名单降级影响。未带版本声明的历史 Token 仅在初始版本 `0` 时兼容。

### `POST /api/auth/change-password`

要求已登录管理员 JWT，同时有 URL 级和方法级权限校验。请求体：

~~~json
{
  "currentPassword": "current-password",
  "newPassword": "replacement-password",
  "confirmPassword": "replacement-password"
}
~~~

新密码至少 8 个 Unicode 字符，UTF-8 编码后不超过 72 字节；确认输入必须一致，且不能与当前密码相同。当前密码验证、写入哈希与版本递增处于同一事务，通过行锁和版本条件防止并发覆盖。成功的 `data` 为 `{ "message": "密码已修改，请重新登录", "relogin": true }`；前端应立即清除本地登录信息并返回登录页。

当前密码错误或输入无效返回业务码 `400`，当前密码连续失败 5 次后返回 `429`，15 分钟后可重试；该限流与登录失败限流分别计数。并发修改冲突返回 `409`，账号存储不可用返回 `503`，不会退回旧环境密码。未认证请求使用 HTTP `401`，无管理员权限使用 HTTP `403`；哈希和明文密码不出现在响应中。

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
| GET | `/api/dashboard/analytics` | 带范围筛选和精确钻取条件的多维统计，见下文 |
| GET | `/api/dashboard/status-distribution` | `[{name, value}]` 状态分布 |
| GET | `/api/dashboard/download-progress` | 下载中项目的 `gid`、`title`、字节数、大小和百分比 |
| GET | `/api/dashboard/db-status` | `connected`、`total`、`totalSizeGb`，失败时含 `error` |
| GET | `/api/dashboard/file-size-distribution` | `{labels, data}` 文件大小分桶 |
| GET | `/api/dashboard/crawl-timeline` | `{dates, counts}` 最近 60 个有数据日期的抓取量 |
| GET | `/api/dashboard/tag-stats` | Top 20 标签命名空间统计 `[{name,nameCn,value}]` |

### `GET /api/dashboard/analytics`

Query：`days=30`、`from`、`to`、`status`、`dedupe=all`、`collectionScope=all`、`collectionId`、`grain=day`。`days=0` 表示全部时间，其他值支持 `1–3650`；`from/to` 为含首尾日期的 `yyyy-MM-dd`。显式 `from` 优先于天数，省略 `to` 时截止今天；仅提供 `to` 且 `days>0` 时向前计算天数。`grain` 支持 `day/week/month`，周以周一为起点。状态、作品版本及合集条件与画廊列表一致。

| `data` 字段 | 内容 |
| --- | --- |
| `overview` | `total`、`size_mb`、`avg_rating`、`avg_pages`、`duplicates`、`imported`、`failed`、`pending`、`linked`、`assigned`、`missing_tags`、`missing_rating`、`missing_pages`、`missing_summary`；平均评分/页数排除未知值 |
| `status/rating/pages/size/dedupe/queries/languages/quality` | `[{name,value,filter}]`；分别为状态、评分、篇幅、体积、版本、来源关键词、语言和缺失元数据分布 |
| `collections` | `[{id,name,value,filter}]`，最多 10 项；未归入合集项的 `id=null` |
| `timeline` | `[{date,value,size_mb,filter}]`，空时间段补零；`filter.from/to` 是该时间段与当前范围相交后的精确日期 |
| `range` | 聚合的实际筛选范围 `{from,to,grain}`；全部时间时日期可为 `null` |
| `timelineRange` | 时间线实际展示范围 `{from,to,grain,truncated}` |
| `generatedAt` | 本次聚合生成时间，ISO 时间字符串 |

`filter` 可直接合并进 `/api/dashboard/galleries` 查询参数；钻取时同时保留 `range` 和当前状态、版本、合集条件。评分/页数/体积使用下文的分桶参数，避免边界记录被重复纳入。语言按完整 `language:...` 标签钻取；多个语言或多个缺失项可同时出现在同一画廊中，计数不是互斥漏斗。

聚合在数据库侧完成，不加载画廊实体或图片；同一筛选组合缓存 30 秒，最多保留 64 组。为限制响应规模，时间线最多展示最近 180 个日段、104 个周段或 120 个月段，超出时 `timelineRange.truncated=true`；其他统计仍使用完整 `range`。

### 标签图谱与全部标签目录

`GET /api/dashboard/tag-network` 支持高频标签网络和全部标签目录两种方式，仍要求管理员 JWT。

| Query 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `limit` | `36` | `0` 表示全部标签目录；其他值限制在 `8–60`，返回高频标签网络 |
| `minWeight` | `2` | 共同出现的最少画廊数，限制在 `1–100`；仅影响连接，不会排除全部目录中的低频标签 |
| `keyword` | 空 | 作品标题包含匹配，最长 500 字符 |
| `offset` | `0` | 全部目录的起始偏移，负数返回业务 `400` |
| `pageSize` | `500` | 全部目录单页标签数，限制在 `50–1000`；普通高频网络不使用此参数 |
| `snapshotId` | 空 | 全部目录首包不传；后续分页必须原样传回首包返回的随机快照标识，与规范化后的 `keyword` 绑定 |

高频网络返回 `nodes`、`links`、`total` 和 `mode=top`；`total` 是当前作品范围内的全部唯一标签数，实际节点最多为 `limit`，连接最多 240 条。空目录时返回空节点、空连接和 `total=0`。节点含 `name`（原始完整标签）、`label`、`namespace`、`value`（标签出现的画廊数）。

`limit=0&offset=0` 首包创建固定 5 分钟的目录快照，返回 `{nodes,links:[],total,hasMore,nextOffset,mode:"all",snapshotId,snapshotSeconds:300}`。客户端使用 `nextOffset` 和同一个 `snapshotId` 请求后续页，并保持 `keyword` 不变，直到 `hasMore=false`。快照内的标签顺序和 `total` 固定，避免加载期间数据更新造成重复或漏项；不会把“全部”替换成一个隐藏的固定数量上限。

后续分页缺少快照标识、快照过期或被逐出、关键词不符时返回业务 `400`，应提示重新加载，不能把不完整结果当作全部标签。单个快照估算内存预算为 32 MiB；首包流式读取并逐行检查预算，超过预算返回业务 `400` 并建议缩小作品标题范围；快照缓存总预算为 64 MiB，空间不足时可能逐出旧快照。5 分钟从首包创建起计算，分页不会续期，标签翻译和分类等元数据也在快照内固定。前端校验快照标识、总数及分页完整性，显示加载进度，可取消；图谱使用 Canvas、空间索引与视口裁剪，侧栏按 50 项分页。全部目录不预先生成连接，点击节点后按需查询直接关联。

`GET /api/dashboard/tag-network/neighbors` 的 Query 为 `tag`（必填，最长 200 字符）、可选 `keyword`、`minWeight=2`（`1–100`）、`limit=100`（`10–200`）。返回 `nodes`、`links`、`totalConnections`、`truncated`；连接的 `value` 为两个标签共同出现的画廊数。`totalConnections` 是当前作品范围和阈值下的全部直接关联数，`truncated=true` 表示仅返回共现最高的 `limit` 个关联，并不表示其余标签不存在。当前前端显示前 100 个关联并提示实际总数。

高频网络和按需关联短缓存 30 秒，分别最多保留 32 组和 128 组；翻译字典统一缓存，避免对每个标签分别发起翻译请求。全部目录分页使用上述固定快照，`snapshotSeconds=300` 表示快照有效期。按需关联仅展开包含目标标签的画廊，不计算全库标签的笛卡尔积；关联统计使用独立短缓存，不与目录快照绑定。

### `GET /api/dashboard/galleries`

分页查询画廊。

| Query 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `page` | `1` | 页码 |
| `size` | `20` | 每页数量，最大 100 |
| `status` | 空 | 英文枚举或中文状态名 |
| `gid` | 空 | 1–1000 个正整数 GID，可用逗号、分号或空格分隔 |
| `keyword` | 空 | 搜索标题、原始标题或文件名，最长 500 字符 |
| `keywordMode` | `contains` | `contains` 连续包含、`all` 全部检索词、`any` 任一检索词、`exact` 整字段精确匹配；`all/any` 支持双引号短语，最多 12 个检索词 |
| `tag` | 空 | 完整标签精确匹配 |
| `tags` / `excludeTags` | 空 | JSON 字符串数组，例如 `["artist:example","language:chinese"]`；各最多 30 项，单项最多 200 字符；标签中的逗号保留为标签本身 |
| `tagMode` | `all` | `tags` 按 `all` 或 `any` 匹配；`excludeTags` 中任一标签命中即排除 |
| `dedupe` | `preferred` | `preferred`、`duplicates` 或 `all` |
| `collectionScope` | `all` | `all`、`assigned`（已归入合集）、`unassigned`（未归入合集） |
| `collectionId` | 空 | 指定正整数合集 ID |
| `minRating/maxRating` | 空 | 含边界的评分范围，取值 `0–5` |
| `minPages/maxPages` | 空 | 含边界的非负页数范围 |
| `minSizeMb/maxSizeMb` | 空 | 含边界的非负 MB 体积范围 |
| `from/to` | 空 | 抓取日期范围，`yyyy-MM-dd`，含首尾日期 |
| `searchQuery` | 空 | 采集时的来源关键词，精确匹配 |
| `komga` | `all` | `all`、`linked`、`unlinked`；按是否有 `komgaBookId` 筛选 |
| `sortBy` | `crawledAt` | `crawledAt`、`gid`、`title`、`downloadStatus`、`fileSizeMb`、`rating`、`pageCount`、`updatedAt` |
| `sortOrder` | `desc` | `asc` 或 `desc`；不支持的排序字段/方向返回业务码 `400` |

用于图表精确钻取的可选条件：

| Query 参数 | 支持值 |
| --- | --- |
| `ratingBucket` | `unrated`、`under3`、`3to4`、`4to4_5`、`4_5plus` |
| `pageBucket` | `unknown`、`under20`、`20to49`、`50to99`、`100plus` |
| `sizeBucket` | `unknown`、`under50`、`50to100`、`100to200`、`200to500`、`500to1024`、`1024plus` |
| `quality` | `missingTags`、`missingRating`、`missingPages`、`missingSummary`、`missingSource` |

分桶上界为不包含，例如 `3to4` 为 `3 <= rating < 4`，`20to49` 为 `20 <= pageCount < 50`；`unrated` 为评分缺失或不大于 0，`unknown` 为相应元数据缺失或不大于 0。筛选条件可组合；上下限倒置、无效日期或标签 JSON 返回业务码 `400`。

响应 `data` 保持分页结构，主要字段为 `records`、`total`、`size`、`current`、`pages`。列表仅查询当前页的轻量元数据，包含标题、评分、页数、标签、摘要、状态、存储位置、去重信息及 `komgaBookId`，不返回源站 token、抓取追踪大 JSON 或内部清理日志。

每条记录额外包含 `collectionId`、`collectionName`、`collectionType`。`collectionType=SERIES` 表示已归入本地自定义合集，`STANDALONE` 表示未归入合集，此时 ID/名称为空；该字段不是 EH 内容题材分类。合集信息按当前页批量补充，不为每行单独查询。V21 为日期排序与首选版本列表增加复合索引；包含匹配和 JSON 标签条件仍可能扫描较多记录，可组合日期、状态、合集等条件收窄范围。

支持的状态为：

`PENDING`、`DOWNLOADING`、`DOWNLOADED`、`WAITING_KOMGA`、`PARTIAL`、`DOWNLOAD_FAILED`、`KOMGA_IMPORT_FAILED`、`IMPORTED`、`REVIEW_REQUIRED`、`BLOCKED`、`IGNORED`。

### 图片分页、封面与 Komga 跳转

以下端点仍要求管理员 JWT：

| 方法 | 路径 | 参数与响应 |
| --- | --- | --- |
| GET | `/api/dashboard/gallery-media/{gid}` | `page=1`、`size=20`、`refresh=false`；`size` 只接受 `10/20/50/100`。返回图片页索引，不下载该页原图 |
| GET | `/api/dashboard/gallery-media/{gid}/cover` | 封面图片二进制 |
| GET | `/api/dashboard/gallery-media/{gid}/images/{index}` | 从 1 开始的图片页码；`thumbnail=false` 返回原图，`true` 返回缩略图二进制 |
| GET | `/api/dashboard/komga-link/{gid}` | JSON `{available,url,message}`；未关联书籍或浏览器地址无效时 `available=false` |

图片分页的 `data` 示例：

~~~json
{
  "gid": 123,
  "page": 1,
  "size": 20,
  "total": 48,
  "source": "KOMGA",
  "available": true,
  "message": "",
  "diagnostics": [],
  "images": [
    {
      "index": 1,
      "name": "第 1 页",
      "thumbnailUrl": "/api/dashboard/gallery-media/123/images/1?thumbnail=true",
      "imageUrl": "/api/dashboard/gallery-media/123/images/1"
    }
  ]
}
~~~

图片源优先使用已挂载且可读的本地 CBZ/ZIP，其次尝试已关联的 Komga 图库，封面采用相同策略。预览索引、缩略图、原图和封面均不请求 EH 源站。`source` 为 `ARCHIVE/KOMGA/NONE`；无可用来源时返回 `available=false`、`total=0`、空 `images`，提示先完成入库或检查归档挂载、Komga 书籍关联。分页超出末页时修正到末页。预览不会为了读取图片而复制 SMB/SFTP 上的整本归档；本地归档只读 ZIP 目录和当前所需条目。

本地定位先检查记录中的目录与文件名；记录路径已迁移或改名时，使用有界、短缓存的图库 GID 索引查找唯一归档，不因每次悬停都递归扫描全库。匹配到多个同 GID 文件、无法读取挂载或达到扫描边界时明确返回诊断，不随意选择候选文件。Komga 保存的 Book ID 返回 `404`，或已入库记录没有可用关联时，会按 GID、标题词召回图库候选，再以物理文件名／URL 中的 GID 或一致文件名确认唯一书籍；标题相似本身不构成匹配。该恢复过程只读，不修改数据库中的书籍关联。

分页响应增加 `diagnostics:[{code,message}]`，用于区分归档未找到、挂载不可读、归档歧义、Komga 未配置、权限不足、旧 Book ID 或书籍无可读页等问题。`NONE` 状态下前端展示具体诊断；恢复找到图库图片时可通过 `message` 显示成功说明。点击“重新检测本地图片”发送 `refresh=true`，仅清除该画廊的预览元数据和来源缓存，同时使本地目录索引失效后重新定位；普通翻页不主动清缓存，不会重新下载或写入归档。

单张原图响应限制为 25 MiB，缩略图最长边 300px；原图仅在点击查看时获取。后端预览并发最多 6 个；缩略图缓存上限 32 MiB / 256 项，图片源索引缓存上限 8 MiB，画廊预览元数据只读取必要字段并短缓存 10 秒。前端图片队列最多并发 4 个，可视区域外图片不加载，离开页面/关闭预览时取消未完成请求。列表悬停满 1 秒才请求一张本地封面，不在列表首次查询时批量预取封面。

图片成功响应按实际格式返回 `Content-Type: image/...`，附 `Cache-Control: private, max-age=300`、`Vary: Authorization` 和 `X-Content-Type-Options: nosniff`。客户端应携带 JWT 请求二进制后创建对象 URL，不把 JWT 写进图片查询参数或外部链接。媒体错误使用 HTTP `400`（参数错误）、`404`（画廊/图片不存在或封面无来源）、`429`（预览并发已满）、`502`（图片源不可用或返回无效图片）、`503`（画廊元数据服务暂不可用）；认证仍使用 `401/403`。

后台封面、缩略图和大图请求的失败只影响图片显示，不清除当前登录凭据或跳转登录页；请求仍携带 JWT，并仍接受服务端权限校验。普通业务接口真正返回 HTTP `401` 或业务 `code=401` 时，前端继续要求重新登录。媒体 `404/502` 等错误保留原有状态，不应在错误分派中被改成认证失败。

Komga 浏览器跳转优先使用 `KOMGA_PUBLIC_URL`，为空时回退 `KOMGA_URL`，保留部署的上下文路径并跳转 `/book/{bookId}`；不回显服务端 API Key，也不携带地址中的凭据、查询参数或片段。浏览器仍需拥有 Komga 自身的访问权限。

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

### 刷新数据库中的旧 Komga ID

入口位于“Komga 入库复核”页面上方，所有接口均要求管理员 JWT。此操作会写入数据库中的 `komga_book_id` 和 `updated_at`，不改变下载状态、不重新下载、不修改归档或 Komga 内容，也不访问 EH 源站。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/komga-id-refresh` | 启动后台刷新。请求体可省略或为 `{}`，默认检查已有关联 ID 或已入库的记录；`{"gids":[1553036]}` 指定最多 1000 个正整数 GID |
| GET | `/api/komga-id-refresh/status` | 读取当前实例最近一次任务进度与结果 |
| POST | `/api/komga-id-refresh/cancel` | 可传 `{"jobId":"当前显示的任务ID"}` 停止该任务；ID 已变化时返回业务 `409`，不停止新任务。不传请求体表示显式停止当前任务；已经成功的更新会保留 |

三者的 `data` 均包含 `jobId`、`running`、`status`、`total`、`processed`、`updated`、`unchanged`、`notFound`、`ambiguous`、`conflicts`、`failed`、`currentGid`、`cancelRequested`、`startedAt`、`finishedAt`、`lastError`、`results`、`resultsTotal`、`resultsTruncated`。`status` 为 `IDLE/INDEXING/RUNNING/COMPLETED/CANCELLED/FAILED`。`results` 项包含 `gid/title/oldId/newId/status/message`；仅保留前 200 条变更或异常明细，`resultsTotal` 计入全部变更及异常，有效且无需修改的关联只计入 `unchanged`。

任务先分页读取配置的 Komga Library 的完整物理书籍索引，再按固定 GID 上界、每批 200 条查询数据库。索引不读取图片，限制为 50000 本和估算 16 MiB；分页不完整、重复 ID、总数发生变化或超过容量时中止，不使用部分索引更新数据库。若旧 ID 已不存在，按物理 `[GID]` 唯一匹配；无 GID 命中时可使用唯一完整文件名（兼容 ZIP/CBZ 扩展名）补救。未找到、多候选、当前 ID 存在但物理身份不符时保留原值，不能仅凭相似标题改关联。

更新以原 ID、文件名和存储目录为条件执行比较并更新，其他任务已更改记录时计入 `conflicts`；成功后使该画廊的预览缓存失效。当前实例只允许一个刷新任务，重复启动返回业务 `409`，输入或配置不合法返回 `400`。任务进度保存在当前进程，重启后不保留；已提交的数据库更新仍然保留，多实例访问此组接口应固定到同一实例。

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
| 认证 | POST | `/api/auth/change-password` |
| 自动化 | POST | `/api/temporal/eh/start` |
| 自动化 | POST | `/api/temporal/eh/retry-failed` |
| 自动化 | POST | `/api/temporal/eh/test-email` |
| Komga | POST | `/api/temporal/eh/collections/build-by-tags` |
| Komga | POST | `/api/temporal/eh/sync-tags` |
| Komga | POST | `/api/temporal/eh/batch-refresh-metadata` |
| Komga | POST | `/api/temporal/eh/batch-update-filesize` |
| Dashboard | GET | `/api/dashboard/stats` |
| Dashboard | GET | `/api/dashboard/analytics` |
| Dashboard | GET | `/api/dashboard/status-distribution` |
| Dashboard | GET | `/api/dashboard/download-progress` |
| Dashboard | GET | `/api/dashboard/db-status` |
| Dashboard | GET | `/api/dashboard/file-size-distribution` |
| Dashboard | GET | `/api/dashboard/crawl-timeline` |
| Dashboard | GET | `/api/dashboard/tag-stats` |
| Dashboard | GET | `/api/dashboard/tag-network` |
| Dashboard | GET | `/api/dashboard/tag-network/neighbors` |
| Dashboard | GET | `/api/dashboard/galleries` |
| 画廊预览 | GET | `/api/dashboard/gallery-media/{gid}` |
| 画廊预览 | GET | `/api/dashboard/gallery-media/{gid}/cover` |
| 画廊预览 | GET | `/api/dashboard/gallery-media/{gid}/images/{index}` |
| 画廊预览 | GET | `/api/dashboard/komga-link/{gid}` |
| Dashboard | GET | `/api/dashboard/suggestions` |
| Dashboard | GET | `/api/dashboard/tag-translations` |
| Dashboard | POST | `/api/dashboard/tag-translations/refresh` |
| Dashboard | GET | `/api/dashboard/tag-detail` |
| 去重审核 | GET | `/api/dedupe-reviews` |
| 去重审核 | POST | `/api/dedupe-reviews/{id}/resolve` |
| 去重审核 | POST | `/api/dedupe-reviews/{id}/ai-review` |
| Komga 复核 | GET | `/api/komga-import-reviews` |
| Komga 复核 | POST | `/api/komga-import-reviews/{gid}/retry` |
| Komga 关联刷新 | POST | `/api/komga-id-refresh` |
| Komga 关联刷新 | GET | `/api/komga-id-refresh/status` |
| Komga 关联刷新 | POST | `/api/komga-id-refresh/cancel` |
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
