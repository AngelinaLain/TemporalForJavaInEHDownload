# TemporalForJavaInEHDow

基于 Spring Boot + Temporal 的分布式工作流引擎，实现 EHentai 画廊内容搜索、下载、数据库管理与媒体库入库的全自动执行。

## 项目概览

该项目基于 **Temporal 工作流框架**，采用 Activity + Workflow 的设计模式，提供 REST API 触发异步工作流任务，实现：

- ✅ 异步接收搜索参数并启动 Temporal 工作流
- ✅ 调用多个 Activity 执行关键业务步骤（爬取、下载、数据库操作、邮件通知等）
- ✅ 完整的错误处理与重试机制（内置可配置的重试策略和不重试错误类型列表）
- ✅ 支持失败任务批量重试工作流
- ✅ 工作流执行过程中的实时状态跟踪和轮询
- ✅ 支持 Microsoft Graph API 邮件通知
- ✅ 支持 Komga 媒体库的自动扫描与元数据补流程

## 技术栈

- **Java**: 17
- **框架**: Spring Boot 3.2.4 + Temporal 工作流引擎
- **数据库**: MyBatis-Plus + MySQL
- **网络**: OkHttp3 + Hutool
- **通知**: Microsoft Graph API
- **媒体库**: Komga（可选集成）

## 项目架构

### 分层设计

```
Controllers Layer (REST API)
    ↓
Temporal Workflow Layer (工作流编排)
    ├── EHAutomationWorkflow (主搜索+下载工作流)
    ├── RetryFailedDownloadWorkflow (重试失败任务工作流)
    └── SingleGalleryDownloadWorkflow (单画廊下载子工作流)
    ↓
Activity Layer (业务逻辑执行单元)
    ├── ScraperActivity (网络爬取)
    ├── DatabaseActivity (数据库操作)
    ├── SynologyActivity (推送下载)
    ├── KomgaActivity (媒体库入库)
    └── NotificationActivity (邮件通知)
    ↓
Support Layer (工具/配置)
    ├── EhNetworkClient (网络客户端)
    ├── Entity & Mapper (数据层)
    └── Config (连接池/超时配置)
```

## 目录结构详解

```text
src/main/java/com/checker/
├─ TemporalForJavaInEHDowApplication.java              # Spring Boot 启动入口
│
├─ controllers/
│  └─ EHAutomationController.java                      # 3 个 REST 端点 (start, retry-failed, test-email)
│
├─ temporalServices/                                   # Temporal 工作流核心
│  ├─ workflows/                                       # 工作流接口定义
│  │  ├─ EHAutomationWorkflow.java                     # 主工作流（搜索+下载完整流程）
│  │  ├─ RetryFailedDownloadWorkflow.java              # 重试工作流
│  │  ├─ SingleGalleryDownloadWorkflow.java            # 单画廊下载子流程
│  │  └─ impl/
│  │     ├─ EHAutomationWorkflowImpl.java               # 主工作流实现
│  │     ├─ RetryFailedDownloadWorkflowImpl.java        # 重试工作流实现
│  │     ├─ SingleGalleryDownloadWorkflowImpl.java      # 单画廊下载实现
│  │     └─ WorkflowSteps.java                         # 共享工具类：统一 ActivityOptions + Komga 流程
│  │
│  ├─ activities/                                      # Activity 接口定义（5 个域）
│  │  ├─ ScraperActivity.java                          # 网络爬取接口
│  │  ├─ DatabaseActivity.java                         # 数据库读写接口
│  │  ├─ SynologyActivity.java                         # 群晖下载推送接口
│  │  ├─ KomgaActivity.java                            # 媒体库入库接口
│  │  ├─ NotificationActivity.java                     # 邮件通知接口
│  │  └─ impl/                                         # Activity 实现类
│  │     ├─ ScraperActivityImpl.java
│  │     ├─ DatabaseActivityImpl.java
│  │     ├─ SynologyActivityImpl.java
│  │     ├─ KomgaActivityImpl.java
│  │     └─ NotificationActivityImpl.java
│  │
│  └─ workers/                                         # Temporal Worker 配置（自动扫描注册）
│
├─ common/
│  ├─ Constants.java                                   # 常量定义（Task Queue 等）
│  ├─ ErrorType.java                                   # 自定义错误类型（不重试列表）
│  ├─ Result.java & ResultCode.java                    # 统一响应格式
│  ├─ DownloadStatus.java                              # 下载状态枚举
│  ├─ EhNetworkClient.java                             # 网络请求客户端（OkHttp3）
│  └─ Other utilities
│
├─ config/
│  ├─ EhNetworkConfig.java                             # 网络配置（代理、Cookie、超时等）
│  └─ EhWorkflowConfig.java                            # Temporal 客户端配置（地址、命名空间等）
│
├─ dto/
│  ├─ SearchOptions.java                               # 搜索参数 DTO（搜索关键词、分类、评分等）
│  └─ WorkflowSettings.java                            # 工作流运行时设置
│
├─ entity/
│  └─ EhGalleriesEntity.java                           # 画廊数据实体（与表 eh_galleries 映射）
│
└─ mapper/
   └─ EhGalleriesMapper.java                           # MyBatis-Plus Mapper（CRUD 操作）

src/main/resources/
└─ application.yaml                                    # Spring Boot 配置（DB、Temporal、代理等）
```

## 工作流执行流程

### 主工作流 (EHAutomationWorkflow)

```
SearchOptions (用户输入)
    ↓
[Activity] Scraper: 网络爬取画廊列表
    ↓
[Activity] Database: 批量保存/更新到 MySQL
    ↓
[For Each Gallery]
    ├─ [Activity] Synology: 推送下载链接
    ├─ [Activity] Database: 更新下载状态
    ├─ [Loop Poll] Database: 轮询下载完成状态
    ├─ [Activity] Komga: 触发元数据补全 (3 步: 文件重命名 → 元数据提取 → 扫描)
    └─ [Activity] Notification: 发送邮件通知 (下载完成)
```

### 重试工作流 (RetryFailedDownloadWorkflow)

```
[Activity] Database: 查询所有failed/downloaded状态的画廊
    ↓
[For Each Failed Gallery]
    └─ [Invoke] SingleGalleryDownloadWorkflow (逐个重新执行下载)
```

## 运行前准备

### 必需服务

1. **JDK 17** — Java 运行时环境
2. **MySQL** — 数据库服务
3. **Temporal Server** — 工作流引擎（关键组件）
4. **Synology Download Station** (可选) — 如需推送下载任务
5. **Microsoft Entra ID App Registration** (可选) — 如需邮件通知
6. **Komga** (可选) — 如需自动入库媒体库

### 数据库初始化

创建数据库与表（MySQL 5.7+）：

```sql
CREATE DATABASE IF NOT EXISTS `eh_automation` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `eh_automation`;

CREATE TABLE `eh_galleries` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `gid` BIGINT NOT NULL UNIQUE,
  `token` VARCHAR(255),
  `title` VARCHAR(500),
  `title_jpn` VARCHAR(500),
  `category` VARCHAR(50),
  `thumb` VARCHAR(500),
  `posted` BIGINT,
  `cover` VARCHAR(500),
  `parent_gid` BIGINT,
  `parent_key` VARCHAR(255),
  `first_gid` BIGINT,
  `first_key` VARCHAR(255),
  `uploader` VARCHAR(200),
  `rating` DECIMAL(3, 2),
  `tags` JSON,
  `download_url` TEXT,
  `download_status` VARCHAR(50) DEFAULT 'PENDING',
  `komga_status` VARCHAR(50) DEFAULT 'PENDING',
  `error_type` VARCHAR(50),
  `error_message` TEXT,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_gid (gid),
  INDEX idx_download_status (download_status),
  INDEX idx_komga_status (komga_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### Temporal Server 启动

使用 Docker Compose 快速启动（推荐）：

```yaml
# docker-compose.yml
version: '3'
services:
  temporal:
    image: temporalio/auto-setup:latest
    environment:
      - DB=postgresql
      - POSTGRES_PWD=temporalp
      - POSTGRES_SEEDS=postgres
    ports:
      - "7233:7233"  # gRPC 端口
    networks:
      - temporal
  
  postgres:
    image: postgres:15
    environment:
      POSTGRES_PASSWORD: temporalp
      POSTGRES_DB: temporal
    ports:
      - "5432:5432"
    networks:
      - temporal

networks:
  temporal:
    driver: bridge
```

运行：

```bash
docker-compose up -d
```

## 配置说明

### application.yaml 关键选项

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/eh_automation?characterEncoding=utf8
    username: root
    password: your_password
  
  temporal:
    connection:
      target: 127.0.0.1:7233          # Temporal Server 地址
    namespace: default                 # 工作流命名空间
    
server:
  port: 8001

eh-config:
  # EHentai 站点 Cookie（从浏览器登录后复制）
  cookies:
    memberid: "your_memberid"
    pass_hash: "your_pass_hash"
    ipb_member_id: "your_ipb_member_id"
    ipb_pass_hash: "your_ipb_pass_hash"
  
  # 代理配置（可选）
  proxy:
    enabled: false
    host: ""
    port: 0
    username: ""
    password: ""
  
  # Synology Download Station（可选）
  synology:
    enabled: false
    host: "192.168.1.100:5000"
    username: "admin"
    password: "password"
    folder: "downloads/eh"
  
  # Microsoft Graph API（邮件通知）
  notification:
    enabled: false
    tenant_id: "your_tenant_id"
    client_id: "your_client_id"
    client_secret: "your_client_secret"
    admin_email: "admin@yourtemplate.onmicrosoft.com"
  
  # Komga（媒体库集成，可选）
  komga:
    enabled: false
    url: "http://komga.example.com"
    api_key: "your_api_key"
    library_id: "xxxx"
```

### 配置说明

| 项 | 说明 | 必需 |
|----|------|------|
| `eh-config.cookies.*` | EHentai 登录 Cookie（从网页开发者工具复制） | ✅ |
| `spring.temporal.connection.target` | Temporal Server 的 gRPC 地址 | ✅ |
| `spring.datasource.*` | MySQL 连接信息 | ✅ |
| `eh-config.proxy.*` | 代理配置 | ❌ |
| `eh-config.synology.*` | 群晖下载任务推送 | ❌ |
| `eh-config.notification.*` | 邮件通知 | ❌ |
| `eh-config.komga.*` | Komga 媒体库集成 | ❌ |

**安全建议**：

- ⚠️ **不要**将真实凭据提交到 Git
- 使用环境变量或 `.properties.local` 覆盖敏感信息
- 在生产环境使用各服务的秘密管理方案（如 Vault、AWS Secrets Manager）

## 编译与启动

### 前置要求

- JDK 17+
- Maven 3.8+（推荐使用 IntelliJ IDEA 内置 Maven）
- MySQL 5.7+ 正常运行
- Temporal Server 正常运行

### 编译

使用 Maven 编译项目：

```bash
mvn clean compile
```

### 启动方式

#### 方式 1：IDE 启动（推荐）

在 IntelliJ IDEA 中：

1. 右键点击 `TemporalForJavaInEHDowApplication` 类
2. 选择 `Run 'TemporalForJavaInEHDowApplication.main()'`

#### 方式 2：Maven 命令行

直接启动：

```bash
mvn clean spring-boot:run
```

#### 方式 3：打包运行

```bash
mvn clean package -DskipTests
java -jar target/TemporalForJavaInEHDow-1.0-SNAPSHOT.jar
```

### 启动验证

应用启动成功后，控制台会输出：

```
c.c.c.TemporalForJavaInEHDowApplication : Started TemporalForJavaInEHDowApplication in 5.xxx seconds
```

服务可访问地址：`http://127.0.0.1:8001`

## API 概览

### 基础路径

所有接口统一前缀：`/api/temporal/eh`

### 端点列表

| 方法 | 路径 | 功能 | 异步 |
|------|------|------|------|
| `POST` | `/start` | 启动主工作流（搜索 + 下载） | ✅ |
| `POST` | `/retry-failed` | 重试失败任务 | ✅ |
| `POST` | `/test-email` | 测试邮件配置 | ❌ |

**详细 API 参数、请求示例、响应格式见：[API_文档.md](API_文档.md)**

## 故障排查

### 常见问题

#### 1. Temporal Server 连接失败

```
io.grpc.StatusRuntimeException: UNAVAILABLE: io exception
```

**解决**：确保 Temporal Server 运行在 `127.0.0.1:7233`

```bash
# 检查 Temporal Server 是否运行
docker ps | grep temporal
```

#### 2. MySQL 连接失败

```
java.sql.SQLException: Client does not support authentication protocol
```

**解决**：检查 MySQL 版本与凭据，更新 `application.yaml` 中的数据库配置

#### 3. EHentai 网站无法访问

```
ErrorType: IP_BANNED (不重试)
```

**解决**：检查网络连接，尝试更新代理配置或 Cookie

#### 4. Synology 推送失败

```
ErrorType: SYNOLOGY_AUTH_FAILED (不重试)
```

**解决**：验证 Synology 地址、用户名、密码是否正确

### 查看日志

实时查看应用日志：

```bash
# 如使用 Maven 启动
tail -f nohup.out

# 如使用 Docker
docker logs -f container_name
```

## 项目统计

- **主要代码行数**：约 2000+ 行 （包含工作流、Activity、配置等）
- **Activity 数量**：5 个（Scraper, Database, Synology, Komga, Notification）
- **Workflow 数量**：3 个（Main, Retry, SingleGalleryDownload）
- **REST 端点**：3 个
- **数据库表**：1 个（eh_galleries）

## 开发与扩展

### 添加新的 Activity

1. 在 `src/main/java/com/checker/temporalServices/activities/` 创建接口
2. 在 `impl/` 下创建实现类（加 `@Component` 注解）
3. 在工作流中调用

示例：

```java
// 创建接口
@ActivityInterface
public interface MyNewActivity {
    @ActivityMethod
    void doSomething(String input);
}

// 创建实现
@Component
public class MyNewActivityImpl implements MyNewActivity {
    @Override
    public void doSomething(String input) {
        // 业务逻辑
    }
}

// 在工作流中使用
MyNewActivity activity = Workflow.newActivityStub(MyNewActivity.class, options);
activity.doSomething("input");
```

### 修改工作流重试策略

编辑 `WorkflowSteps.java` 修改 `DEFAULT_OPTIONS` 和 `SCRAPER_OPTIONS`：

```java
static final ActivityOptions DEFAULT_OPTIONS = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(10))
                .setMaximumAttempts(3)  // 最多重试 3 次
                .build())
        .build();
```

## 相关资源

- [Temporal 官方文档](https://docs.temporal.io/)
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [Spring Boot Temporal Starter](https://github.com/temporalio/temporal-spring-boot-starter)
- [EHentai API 参考](https://ehwiki.org/wiki/API)
- [MyBatis-Plus 文档](https://baomidou.com/)

## 许可证

MIT

## 联动二次开发

如需 Activity 扩展或新工作流集成，请参考上述"开发与扩展"部分或联系项目维护者。

- `API_文档.md`

## 工作流说明

主流程（`EHAutomationWorkflow`）核心步骤：

1. `scrapeGalleries` 抓取结果
2. `saveToDatabase` 入库/更新
3. `extractDownloadUrl` 解析直链
4. `pushToSynology` 下发下载任务
5. `checkSynologyTaskStatus` 轮询状态
6. `updateGalleryStatus` 回写状态
7. `fetchAndSaveMetadata` / `triggerKomgaLibraryScan` / `pushMetadataToKomga` 后处理

重试流程（`RetryFailedDownloadWorkflow`）：

- 从数据库提取失败记录后重新拉取直链并重推下载
- 已下载但未入库的记录会走 Komga 补偿逻辑

## 状态字段

`eh_galleries` 中常见状态值：

- `未下载`
- `下载中`
- `已下载`
- `下载失败`
- `已入库`
- `阻断`

## 常见排查

1. 接口返回成功但无下载任务
   - 检查 `eh-config.synology.*` 与下载目的地权限

2. 工作流启动失败
   - 检查 Temporal 地址是否可达、Task Queue 是否一致（`EHDownloadTaskQueue`）

3. 下载状态长期不变化
   - 检查群晖接口权限、网络代理、目标站点可访问性

4. 邮件发送失败
   - 检查 Microsoft Graph 的 `tenant-id/client-id/client-secret/sender-email`

## 开发建议

- 保持 `SearchOptions` 与接口文档参数同步
- 仅在必要时调整 Activity 重试策略和不可重试异常类型
- 为生产环境增加脱敏配置和密钥管理方案

## 参考资料

- 详细 API 文档：`API_文档.md`
- Maven：`D:\IntelliJ IDEA 2024.2.0.1\plugins\maven\lib\maven3\bin`
