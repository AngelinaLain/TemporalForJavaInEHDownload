# TemporalForJavaInEHDow

基于 **Spring Boot + Temporal** 的 EHentai 自动化下载与 Komga 入库系统。通过 REST API 触发异步工作流，完成从搜索抓取、Synology 下载推送到 Komga 元数据同步的全链路自动化。

## 核心功能

- EHentai 画廊搜索与数据抓取，支持多维度过滤
- Temporal 异步工作流，内置重试与容错机制
- MySQL 持久化画廊状态与元数据
- Synology Download Station 下载任务推送
- Komga 自动入库、标签同步与合集管理
- Microsoft Graph API 邮件通知
- JWT 认证 + Swagger UI
- Dashboard 统计面板（状态分布、时间线、标签分析）

## 技术栈

| 类别 | 组件 |
| --- | --- |
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.2.4 |
| 工作流引擎 | Temporal Java SDK |
| ORM | MyBatis-Plus |
| 数据库 | MySQL 8+ |
| HTTP 客户端 | OkHttp3 |
| 工具库 | Hutool |
| 安全 | Spring Security + JWT |
| API 文档 | Springdoc OpenAPI (Swagger) |

## 目录结构

```text
src/main/java/com/checker/
├── TemporalForJavaInEHDowApplication.java
├── common/                    # 通用工具：Result、ResultCode、ErrorType、枚举等
├── config/                    # JWT、Security、网络、Workflow 配置
├── controllers/
│   ├── AuthController.java    # 登录认证
│   ├── EHAutomationController.java  # 工作流触发接口
│   └── DashboardController.java     # 统计看板接口
├── dto/                       # 请求数据对象：SearchOptions、KomgaCollectionRequest 等
├── entity/                    # 数据库实体：EhGalleriesEntity
├── mapper/                    # MyBatis-Plus Mapper
├── service/                   # 业务逻辑层
└── temporalServices/
    ├── activities/            # Temporal Activity 接口与实现
    ├── workflows/             # Temporal Workflow 接口与实现
    └── workers/               # Temporal Worker 注册
```

## 快速启动

### 依赖要求

- JDK 17
- MySQL 8+
- Temporal Server（本地或远程）
- Synology Download Station（可选）
- Komga（可选）
- Microsoft 365 / Graph API（可选，用于邮件通知）

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

### 2. 修改配置

编辑 `src/main/resources/application.yaml`，按实际环境填写：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/eh_automation
    username: your_user
    password: your_password
  temporal:
    connection:
      target: localhost:7233
      namespace: default

eh-config:
  cookies: "ipb_member_id=xxx; ipb_pass_hash=xxx; ..."
  proxy:
    host: 127.0.0.1
    port: 7890
  synology:
    url: http://your-nas:5000
    username: admin
    password: your_password
  komga:
    url: http://your-komga:25600
    username: admin@example.com
    password: your_password
  notification:
    tenant-id: xxx
    client-id: xxx
    client-secret: xxx
    sender: admin@yourdomain.com

security:
  admin:
    username: admin
    password: your_password
```

> 仅 `datasource`、`temporal`、`eh-config.cookies` 为必填项，其余按需配置。

### 3. 构建与运行

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/TemporalForJavaInEHDow-1.0-SNAPSHOT.jar
```

或直接开发模式运行：

```bash
mvn spring-boot:run
```

### 4. 访问地址

| 服务 | 地址 |
|------|------|
| 应用默认端口 | `http://127.0.0.1:8001` |
| Swagger UI | `http://127.0.0.1:8001/swagger-ui/index.html` |
| OpenAPI JSON | `http://127.0.0.1:8001/v3/api-docs` |

## 认证

所有业务接口受 JWT 保护。先调用登录接口获取 token，再在后续请求头中携带：

```http
Authorization: Bearer <jwt-token>
```

**登录示例：**

```bash
curl -X POST http://127.0.0.1:8001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your_password"}'
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

详细接口说明见 [API_文档.md](API_文档.md)。
