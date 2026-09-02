# GalleryImport

GalleryImport 是一套基于 Temporal 的 EHentai 画廊自动化导入系统，覆盖画廊抓取、重复作品判断、下载、`ComicInfo.xml` 注入、群晖上传、Komga 扫描与元数据处理，并提供人工审核、运行监控和汇总邮件。

## 主要能力

- 按关键词抓取画廊，保存标题、原始标题、标签、评分、页数、简介等信息。
- 使用候选桶与多信号评分判断同一作品的不同版本，灰区结果进入人工审核。
- 默认由后端流式下载，支持断点续传、持久化缓存、ZIP/CRC 深度校验和下载进度展示。
- 自动生成并注入 `ComicInfo.xml`，然后上传为 Komga 可识别的 `.cbz`。
- 群晖上传优先使用 SMB/CIFS，失败时自动降级到 SFTP；也可切回 Download Station 模式。
- 等待全部子工作流结束后只发送一封汇总邮件，不再每个子流程发送一封。
- 提供画廊管理、重复项审核、操作入口、监控大盘和 Temporal 工作流管理页面。

## 当前处理链路

1. 父工作流按搜索条件调用 `scraper-worker` 抓取画廊。
2. `main-service` 写入数据库，并生成去重键、候选键和可解释评分。
3. 自动命中的重复版本只保留首选版本；灰区版本标记为 `REVIEW_REQUIRED`，等待人工处理。
4. 下载子工作流按配置选择本地下载或 Synology Download Station。
5. 本地模式完成下载、校验、`ComicInfo.xml` 注入、CBZ 重命名和群晖上传。
6. Komga 扫描文件并完成书籍识别，系统随后修正元数据与合集关系。
7. 最外层父工作流等待所有已启动的子工作流结束，再根据数据库最终状态发送一封汇总邮件。

Temporal 工作流使用版本标记兼容已存在的历史记录，升级后不需要终止旧流程。

## 下载实现

### 本地下载（默认）

设置 `DOWNLOAD_MODE=local` 后，每个画廊使用独立目录：

```text
${DOWNLOAD_TEMP_DIR}/gallery-{gid}/
├── archive.zip.part       # 下载中的分片
├── archive.zip.part.meta  # 断点续传元数据
├── archive.zip            # 下载完成、等待注入
├── final.cbz              # 已注入 ComicInfo.xml
└── comicinfo.sha256       # 元数据指纹
```

实现特性：

- HTTP 流式写盘，不会把整个压缩包读入内存。
- 使用 `.part` 和响应校验信息续传；服务或容器重启后可以复用缓存。
- 同一 GID 使用文件锁，避免并发 Activity 同时修改同一缓存。
- 下载后完整读取 ZIP 条目，校验中央目录、CRC、图片条目和异常解压体积。
- `ComicInfo.xml` 包含标题、系列、简介、作者/社团与标签；元数据变化时只重新注入，不重复下载。
- 上传采用临时远端文件、大小校验和重命名发布，降低 Komga 扫描到半成品的概率。
- 下载进度写入数据库，同时通过 Temporal heartbeat 报告长任务存活状态。

Docker Compose 默认把 `/data/download-cache` 挂载到命名卷 `download-cache`。缓存清理规则如下：

- 上传成功并且文件名写入数据库后，删除该 GID 的本地工作目录；持久卷本身不会被删除。
- 下载、注入或上传失败时保留有效缓存，下一次重试从可复用阶段继续。
- ZIP/CRC 校验失败时删除损坏的归档和断点文件，再重新下载。

最终文件名规则为：

```text
[gid] 清理后的标题.cbz
```

文件名中的 `\ / : * ? " < > |`、控制字符会替换为 `_`，末尾的点和空格会移除，并按 UTF-8 字节数截断，避免群晖路径非法或过长。

### Download Station 兼容模式

设置 `DOWNLOAD_MODE=downloadstation` 可沿用群晖 Download Station 下载与轮询逻辑。该模式主要用于现有环境兼容；新部署建议使用本地模式，以获得断点续传、归档校验和 `ComicInfo.xml` 注入能力。

## 重复作品判断

判重分两步进行：

1. 对标准化标题生成 `candidate_key`，先缩小可能相同的候选范围。
2. 对候选画廊按多个信号计算 0–100 分，并记录 `dedupe_match_reason`。

当前评分信号包括原始标题/标准化标题、标题相似度、作者或社团、原作、角色和页数接近程度。阈值为：

| 分数 | 处理方式 |
| --- | --- |
| `>= 85` | 自动视为同一作品并聚类 |
| `65–84` | 标记为 `REVIEW_REQUIRED`，进入人工审核 |
| `< 65` | 视为不同作品 |

自动选择首选版本时依次比较评分、页数和 GID；人工选择的首选 GID 优先级最高。下载完成后还会结合最终健康状态重新选择，避免失败版本压住已成功导入的版本。

### 人工审核

前端“重复项审核”页面会并排显示两个候选版本，包括标题、原始标题、标签、评分、页数、状态、匹配分数、匹配原因和推荐版本。审核人员可以：

- 选择“同一作品”，并指定保留的首选 GID；
- 选择“不同作品”，让两个版本分别进入下载流程。

审核结论保存到 `eh_dedupe_reviews`，后续抓取、重启和重新聚类都会复用，不会被下一次自动评分覆盖。候选桶和审核记录使用数据库锁，避免审核与下载认领并发冲突。

## Komga 扫描说明

文件上传后先标记为 `WAITING_KOMGA`，系统再触发并轮询 Komga。只有在目标 Library、`N8N_Update` 系列和数据库文件名（`[gid] 标题.cbz`）唯一匹配并取得 BookID，且幂等元数据更新成功后，才标记为 `IMPORTED`。多个精确候选、扫描接口失败或等待超时会标记为 `KOMGA_IMPORT_FAILED`，不会提前显示为已入库。

Komga 的扫描、媒体分析和数据库刷新是异步过程，因此“确认扫描命中”通常是整个链路中较慢的一段，这是正常现象。默认每 15 秒查询一次、最多 40 次（约 10 分钟），约 2 分钟仍未命中时会补触发一次扫描。

### Komga 入库复核（阶段二）

`KOMGA_IMPORT_FAILED` 记录会保存确认次数、最近原因和候选 BookID，可在前端“Komga 入库复核”页面查看。点击“仅重试 Komga”只重新触发扫描和入库确认，不会重新下载或覆盖已有缓存文件；重试期间状态回到 `WAITING_KOMGA`。

可通过 `eh-config.workflow` 下的以下配置调整等待策略；启用 `prod` profile 时可使用对应环境变量：

- `KOMGA_IMPORT_MAX_RETRIES`：最大轮询次数，默认 40。
- `KOMGA_IMPORT_POLL_INTERVAL_SECONDS`：轮询间隔秒数。

## 项目结构

```text
GalleryImport/
├── common/           # 通用模型、网络客户端、ComicInfo 与判重算法
├── main-service/     # API、Temporal Worker、下载导入、Komga、审核和 Flyway
├── scraper-worker/   # EHentai 搜索与画廊抓取
├── ai-service/       # AI 简介/翻译服务
├── 前端/              # Vue 3 管理界面
├── deploy/           # Prometheus、Grafana、Loki、Promtail 配置
├── Dockerfile
└── docker-compose.yml
```

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2.4、Spring Cloud、MyBatis-Plus |
| 编排 | Temporal Java SDK 1.31 |
| 数据 | MySQL、Flyway、Redis |
| 网络与存储 | OkHttp、SMBJ、SFTP |
| 前端 | Vue 3、Vite 5、Element Plus、Pinia、ECharts |
| 可观测性 | Actuator、Prometheus、Grafana、Loki、Promtail、Zipkin |
| 基础设施 | Nacos、Docker Compose |

## 快速开始

生产环境 Docker 部署请参阅 [部署文档](./DEPLOYMENT.md)。

### 1. 前置依赖

- JDK 17
- Maven 3.9+
- Node.js 18+
- Docker 与 Docker Compose（容器部署时）
- 可访问的 MySQL、Temporal、Nacos、EHentai、群晖和 Komga

### 2. 配置环境变量

复制示例文件，并填写真实配置：

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

主要配置分组：

| 分组 | 变量 |
| --- | --- |
| 基础设施 | `NACOS_SERVER_ADDR`、`DB_*`、`TEMPORAL_*`、`REDIS_*` |
| 应用安全 | `JWT_SECRET`、`ADMIN_USERNAME`、`ADMIN_PASSWORD_HASH`、`CORS_ALLOWED_ORIGINS` |
| EHentai | `EH_MEMBER_ID`、`EH_PASS_HASH`、`EH_SK`、`EH_STAR`、`PROXY_*` |
| 群晖 | `SYNOLOGY_*`、`SMB_*` |
| Komga | `KOMGA_URL`、`KOMGA_API_KEY`、`KOMGA_LIBRARY_ID` |
| 通知 | `NOTIFICATION_ADMIN_EMAIL`、`GRAPH_*` |
| 下载 | `DOWNLOAD_MODE`、`DOWNLOAD_TEMP_DIR` |
| 可观测性 | `ZIPKIN_URL`、`TRACING_SAMPLE_RATE`、`GRAFANA_ADMIN_PASSWORD` |

不要提交 `.env`。`JWT_SECRET` 应使用至少 32 字节的随机值，管理员密码应先生成 BCrypt 哈希后写入 `ADMIN_PASSWORD_HASH`。

### 3. Docker Compose 启动

```bash
docker compose up -d --build
```

启用完整可观测性组件：

```bash
docker compose --profile observability up -d --build
```

Grafana 会自动预置 Prometheus、Loki 和 `GalleryImport Monitoring` 大盘；登录管理前端后进入 `/monitoring` 即可直接打开。

默认端口：

| 服务 | 地址 |
| --- | --- |
| 管理前端 | `http://localhost:8002` |
| 后端 API | `http://localhost:8001` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000/d/galleryimport-monitoring/galleryimport-monitoring` |
| Zipkin | `http://localhost:9411` |
| Loki | `http://localhost:3100` |

MySQL、Temporal 和 Nacos 目前由外部环境提供，不在 `docker-compose.yml` 中创建。

### 4. 本地构建

后端：

```bash
mvn clean test
mvn clean package -DskipTests
```

前端：

```bash
cd 前端
npm ci
npm run build
```

## 数据库迁移

应用启动时由 Flyway 自动执行迁移，不需要手工创建表。

| 版本 | 内容 |
| --- | --- |
| V1 | 创建 `eh_galleries` 基础表 |
| V2 | 增加简介与查询索引 |
| V3 | 增加首版作品指纹与重复关系字段 |
| V4 | 增加增量同步检查点和更新时间 |
| V5 | 增加本地下载字节进度 |
| V6 | 增加候选键与去重算法版本（保留已发布迁移的校验兼容） |
| V7 | 增加持久化人工审核记录 |
| V8 | 升级为候选检索、并发锁与多信号评分判重 |
| V9 | 持久化 Komga 入库确认进度，增加失败复核与仅 Komga 补偿入口 |

迁移文件位于 `main-service/src/main/resources/db/migration/`。

## 管理页面

登录后可访问：

- `/dashboard`：整体状态与趋势。
- `/galleries`：画廊列表、状态、下载进度与详情。
- `/dedupe-reviews`：重复候选人工审核。
- `/komga-import-reviews`：Komga 入库失败查看与仅 Komga 补偿。
- `/operations`：抓取、重试等操作入口。
- `/monitoring`：Grafana 监控大盘。
- `/workflows`：Temporal 工作流列表、历史和终止操作。

## 主要接口

### 重复项审核

- `GET /api/dedupe-reviews`：分页查询审核记录，可按结论过滤。
- `POST /api/dedupe-reviews/{id}/resolve`：提交 `MATCH` 或 `DIFFERENT`，必要时派发下载工作流。

### Temporal 监控

- `GET /api/temporal/monitor/workflows`：查询工作流。
- `GET /api/temporal/monitor/workflows/{workflowId}/history`：查询执行历史。
- `POST /api/temporal/monitor/workflows/{workflowId}/terminate`：终止工作流。

完整接口说明参见 [API_文档.md](./API_文档.md)。

## 邮件通知

新启动的批处理流程只在所有已启动子流程完成后发送一封汇总邮件，内容包括计划数、启动数、完成状态分布和致命错误提示。单画廊流程中的逐条成功/失败邮件仅为旧 Temporal 历史兼容路径保留。

## 运维建议

- 不要使用 `docker compose down -v`，除非确认可以删除 Redis、Grafana 和下载缓存卷。
- 失败任务恢复前先保留 `download-cache`，否则会失去断点续传数据。
- Komga 长时间未命中时，依次检查群晖目标路径、Komga Library ID、文件是否已原子发布以及 Komga 扫描队列。
- 生产环境建议启用 Redis，以共享 JWT 黑名单和 AI 翻译缓存。
- 默认关闭 Zipkin 采样；启动 Zipkin 后再设置 `TRACING_SAMPLE_RATE`。

## 安全说明

- 仓库只保留变量名和示例值，不保存真实 Cookie、数据库密码、Graph 凭据或 NAS 凭据。
- 后端接口使用 JWT 鉴权，并支持 Redis 黑名单注销。
- 如果凭据曾进入 Git 历史，应立即轮换；仅从当前文件中删除并不能使旧凭据失效。
