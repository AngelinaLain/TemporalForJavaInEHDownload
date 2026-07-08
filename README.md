# GalleryImport (TemporalForJavaInEHDow)

基于 **Spring Boot 3.2.4 + Spring Cloud Alibaba + Temporal** 的 EHentai 自动化下载与 Komga 入库系统。
采用 **Maven 多模块 / 微服务** 架构，通过 Nacos 服务发现 + Temporal 工作流编排，完成从
「搜索抓取 → Synology 下载 → Komga 元数据刮削与合集 → 邮件通知」的全链路自动化。


## 核心功能

- EHentai / ExHentai 画廊搜索与数据抓取（多维度过滤，独立爬虫 Worker）
- Temporal 异步工作流：子工作流隔离 + 滑动窗口并发 + 惰性直链提取 + 补偿队列
- MySQL 持久化画廊状态与元数据（MyBatis-Plus）
- Synology Download Station 下载任务推送与文件重命名
- Komga 自动入库、异步轮询元数据、标签同步与合集管理
- AI 标签翻译服务（Spring AI，可指向本地 LM Studio / OpenAI 兼容端点）
- Microsoft Graph API 邮件通知
- JWT 认证 + Swagger UI
- Vue3 Dashboard 后台（状态分布、时间线、标签分析、运维操作）

## 技术栈

| 类别 | 组件 |
| --- | --- |
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.2.4 |
| 微服务 | Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0（Nacos Discovery + LoadBalancer） |
| 工作流引擎 | Temporal Java SDK 1.31.0 |
| ORM | MyBatis-Plus 3.5.5 |
| 数据库 | MySQL 8+ |
| HTTP 客户端 | OkHttp3 |
| 工具库 | Hutool 5.8.38、Jsoup 1.17.2、JSch 0.1.55 |
| AI | Spring AI 0.8.1（OpenAI 兼容 starter） |
| 安全 | Spring Security + JWT (jjwt 0.12.5) |
| 缓存 | Caffeine |
| API 文档 | Springdoc OpenAPI (Swagger) |
| 前端 | Vue 3.4 + Vite 5 + Pinia + ECharts 5 + Axios |

## 模块结构

顶层 `pom.xml` 为聚合父 POM（`packaging=pom`），继承 `spring-boot-starter-parent`，聚合以下模块：

```text
GalleryImport/                     # 父 POM：统一依赖版本管理
├── common/                        # 公共库 (jar)：被 main-service / scraper-worker 依赖
│   └── com.checker
│       ├── common/                # Result、ResultCode、ErrorType、Constants、
│       │                          #   DownloadStatus、EhNetworkClient
│       ├── config/                # EhNetworkConfig（Cookie/代理配置）
│       ├── dto/                   # ArchiveDownloadInfo、SearchOptions   ← 单一来源
│       ├── entity/                # EhGalleriesEntity                     ← 单一来源
│       └── temporalServices/activities/ScraperActivity   # 爬虫 Activity 接口 ← 单一来源
│
├── main-service/                  # 主控服务 (jar)，端口 8001
│   └── com.checker
│       ├── MainServiceApplication.java
│       ├── config/                # JWT、Security、Cache、MybatisPlus、Network、EhWorkflow
│       ├── controllers/           # Auth / EHAutomation / Dashboard
│       ├── clients/               # KomgaApiClient、SynologyApiClient
│       ├── dto/                   # KomgaCollectionRequest、WorkflowSettings（本模块特有）
│       ├── mapper/                # EhGalleriesMapper
│       ├── service/               # EhGalleries / EhTagTranslation / KomgaSync / KomgaCollection
│       └── temporalServices/
│           ├── activities/        # Database / Komga / Notification / Synology / Ai（接口 + impl）
│           └── workflows/         # EHAutomation / SingleGalleryDownload /
│                                  #   RetryFailedDownload / KomgaImport（接口 + impl + WorkflowSteps）
│
├── scraper-worker/                # 爬虫 Worker (jar)，端口 8081
│   └── com.checker
│       ├── ScraperWorkerApplication.java
│       └── temporalServices/ScraperActivityImpl.java   # 实现 common 的 ScraperActivity
│
├── ai-service/                    # AI 服务 (jar)，端口 8082（独立，不依赖 common）
│   └── com.checker
│       ├── AiServiceApplication.java
│       └── controllers/AiController.java
│
└── 前端/                          # eh-admin：Vue3 + Vite 后台，端口 8002（Nginx）/ 5173（dev）
    └── src
        ├── api/index.js
        ├── layout/AdminLayout.vue
        ├── router/index.js
        ├── stores/tagStore.js
        └── views/  Dashboard.vue | Galleries.vue | Operations.vue | Login.vue
```

### 模块依赖关系

```text
common  ←──  main-service      (完整业务：Web / Security / MyBatis / Temporal 编排)
   ↑
   └──────  scraper-worker     (仅 Temporal，注册 ScraperActivityImpl)

ai-service                     (独立，Web + Nacos + Spring AI，不依赖 common)
前端                            (通过 /api 反代 main-service:8001)
```

## Temporal 工作流拓扑

三条 **Task Queue** 实现职责隔离（定义于 `common.Constants`）：

| Task Queue 常量 | 值 | 由谁消费 | 用途 |
| --- | --- | --- | --- |
| `TASK_QUEUE` | `EHDownloadTaskQueue` | main-service | 主工作流、DB/Komga/Synology/Notification Activity |
| `SCRAPER_TASK_QUEUE` | `EH_SCRAPER_TASK_QUEUE` | scraper-worker | 爬虫抓取（旁路由节点执行，带心跳） |
| `AI_TASK_QUEUE` | `EH_TASK_QUEUE` | ai-service / AiActivityImpl | LLM 标签翻译 |

**工作流清单：**

| 工作流 | 说明 |
| --- | --- |
| `EHAutomationWorkflow` | 主流程：抓取 → 批量分类（跳过/补偿/新下载）→ 滑动窗口派发子工作流 → 汇总通知 |
| `SingleGalleryDownloadWorkflow` | 单画廊子工作流：隔离下载/轮询逻辑，规避主工作流历史 5 万条上限 |
| `RetryFailedDownloadWorkflow` | 失败任务重试 |
| `KomgaImportWorkflow` | Komga 入库子流程 |

**关键设计（见 `EHAutomationWorkflowImpl` / `WorkflowSteps`）：**
- 子工作流隔离，避免主工作流历史爆炸
- 滑动窗口并发（`WorkflowSettings.maxConcurrency`，运行时可调）
- 惰性直链提取，防止排队过久链接过期
- 批量 DB 查询/保存，替代逐条 Activity 调用
- 分级重试：DB 快速重试 3 次；爬虫指数退避（30s→…→30min，最多 5 次）+ 30s 心跳；
  Cookie 失效 / IP 封禁 / 配额超限等致命错误标记为不可重试，交由人工介入
- Komga 入库异步轮询（默认 20 次 × 15 秒），超时发邮件告警

## 基础设施依赖

| 组件 | 默认地址 |
| --- | --- |
| Nacos Discovery | `10.10.10.175:8848` |
| Temporal Server | `10.10.10.161:7233`（namespace: `default`） |
| MySQL | `10.10.10.161:3306/eh_automation` |
| Synology | `https://10.10.10.40:5001` |
| Komga | `http://10.10.10.40:3000` |
| 代理 (Clash) | `10.10.10.32:7893` |
| AI 端点 (LM Studio) | `http://10.10.10.50:1234` |

> 以上为默认值，均可通过 `application.yaml` 或环境变量覆盖。

## 快速启动

### 依赖要求

- JDK 17、Maven 3.9+
- MySQL 8+、Temporal Server、Nacos
- Synology Download Station、Komga（可选）
- OpenAI 兼容 LLM 端点（可选，AI 标签翻译）
- Microsoft 365 / Graph API（可选，邮件通知）

### 1. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS `eh_automation`
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `eh_automation`;

CREATE TABLE `eh_galleries` (
  `gid`                        BIGINT PRIMARY KEY,
  `token`                      VARCHAR(255),
  `title`                      VARCHAR(500),
  `filename`                   VARCHAR(500),
  `gallery_url`                VARCHAR(500),
  `search_query`               VARCHAR(500),
  `crawled_at`                 TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `download_status`            VARCHAR(50) DEFAULT 'PENDING',
  `tags`                       JSON,
  `komga_book_id`              VARCHAR(255),
  `file_size_mb`               DOUBLE,
  `_trace_pages_crawled`       INT,
  `_trace_stop_reason`         VARCHAR(200),
  `_trace_last_next_cursor`    VARCHAR(500),
  `_trace_request_url_chain`   TEXT,
  `_trace_first_page_title`    VARCHAR(500),
  `_trace_page_trace`          JSON,
  INDEX idx_download_status (`download_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2. 配置

各模块 `src/main/resources/application.yaml` 已内置局域网默认值，敏感项建议用环境变量覆盖：

- `main-service`：`DB_PASSWORD`、`JWT_SECRET`、`ADMIN_USERNAME/PASSWORD`、
  `EH_MEMBER_ID/EH_PASS_HASH/EH_SK/EH_STAR`、`PROXY_*`、`SYNOLOGY_*`、
  `GRAPH_*`、`KOMGA_API_KEY` 等
- `scraper-worker`：`EH_*`、`PROXY_*`
- `ai-service`：`spring.ai.openai.base-url` / `model`

`main-service` 的工作流运行时参数（`eh-config.workflow`）可在线调整，无需重新编译：
`max-concurrency`、`komga-import-max-retries`、`komga-import-poll-interval-seconds`、
`download-poll-interval-minutes`、`download-cooldown-seconds`。

### 3. 构建

```bash
# 在项目根目录，聚合构建全部模块
mvn clean package -DskipTests
```

### 4. 运行（需先启动 Nacos + Temporal + MySQL）

```bash
# 主控服务（8001）
java -jar main-service/target/main-service-1.0-SNAPSHOT.jar

# 爬虫 Worker（8081）
java -jar scraper-worker/target/scraper-worker-1.0-SNAPSHOT.jar

# AI 服务（8082，可选）
java -jar ai-service/target/ai-service-1.0-SNAPSHOT.jar

# 前端（dev）
cd 前端 && npm install && npm run dev   # http://127.0.0.1:5173
```

### 5. Docker Compose（后端 + 前端）

```bash
docker compose up -d
# backend  → :8001
# frontend → :8002（Nginx 反代 /api → backend:8001）
```

> 注：`docker-compose.yml` 目前仅编排 `main-service`（backend）与前端；
> `scraper-worker`、`ai-service`、Nacos、Temporal、MySQL 需另行部署。

### 6. 访问地址

| 服务 | 地址 |
| --- | --- |
| 主控 API | `http://127.0.0.1:8001` |
| Swagger UI | `http://127.0.0.1:8001/swagger-ui/index.html` |
| OpenAPI JSON | `http://127.0.0.1:8001/v3/api-docs` |
| 前端（Docker） | `http://127.0.0.1:8002` |
| 前端（dev） | `http://127.0.0.1:5173` |

## 认证

所有业务接口受 JWT 保护。先登录获取 token，再在请求头携带：

```http
Authorization: Bearer <jwt-token>
```

```bash
curl -X POST http://127.0.0.1:8001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"***"}'
```

## API 概览

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

详细接口说明见 [API_文档.md](API_文档.md)，后续优化规划见 [修复意见.md](修复意见.md)。
