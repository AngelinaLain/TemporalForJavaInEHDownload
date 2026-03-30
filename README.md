# TemporalForJavaInEHDow

基于 Spring Boot + Temporal 的 EH 自动化处理服务，提供 REST API 触发工作流，实现从检索、入库、下载、状态跟踪到通知的全链路自动化。

## 项目概览

该项目主要能力：

- 接收搜索参数并异步启动 Temporal 工作流
- 抓取画廊列表并持久化到 MySQL
- 解析下载链接并推送到 Synology Download Station
- 轮询下载状态并回写数据库
- 支持失败任务重试工作流
- 支持 Microsoft Graph 邮件通知
- 支持触发 Komga 扫描与元数据补偿流程

## 技术栈

- Java 17
- Spring Boot 3.2.4
- Temporal Java SDK (`temporal-spring-boot-starter`)
- MyBatis-Plus + MySQL
- OkHttp / Hutool

## 目录结构

```text
src/main/java/com/checker
├─ TemporalForJavaInEHDowApplication.java      # Spring Boot 启动入口
├─ controllers/EHAutomationController.java     # REST 接口
├─ dto/SearchOptions.java                      # 搜索参数对象
├─ temporalServices/workflows                  # Workflow 接口与实现
├─ temporalServices/activities                 # Activity 接口与实现
├─ mapper/EhGalleriesMapper.java               # 数据访问
├─ entity/EhGalleriesEntity.java               # 数据实体
└─ config                                      # 网络与 Temporal 配置

src/main/resources
└─ application.yaml                            # 运行配置
```

## 运行前准备

请先确保以下依赖可用：

1. JDK 17
2. MySQL（并创建数据库 `eh_automation`，准备表 `eh_galleries`）
3. Temporal Server（默认命名空间 `default`）
4. Synology Download Station（如需下载链路）
5. 可访问目标站点的网络代理（如你的环境需要）

## 配置说明

配置文件位于 `src/main/resources/application.yaml`。

关键配置项：

- `spring.datasource.*`：MySQL 地址与账号
- `spring.temporal.connection.target`：Temporal 地址
- `server.port`：服务端口（默认 `8001`）
- `eh-config.cookies.*`：目标站点 Cookie
- `eh-config.proxy.*`：代理配置
- `eh-config.synology.*`：群晖地址与凭据
- `eh-config.notification.*`：邮件通知（Microsoft Graph）
- `eh-config.komga.*`：Komga 集成配置

安全建议：

- 请将真实凭据替换为你自己的环境变量或本地私有配置
- 避免将生产凭据直接提交到仓库

## 启动方式

### 方式 1：IDE 启动（推荐）

直接运行 `TemporalForJavaInEHDowApplication` 主类。

### 方式 2：Maven 命令

```bash
mvn clean spring-boot:run
```

打包运行：

```bash
mvn clean package
java -jar target/TemporalForJavaInEHDow-1.0-SNAPSHOT.jar
```

## API 概览

基础路径：`/api/temporal/eh`

- `POST /start`：启动自动化主流程
- `POST /retry-failed`：重试数据库中失败任务
- `POST /test-email`：测试邮件通知

完整 API 参数、请求示例、错误码说明见：

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
- 设计/参数补充：`参照/params.md`
- Maven：`D:\IntelliJ IDEA 2024.2.0.1\plugins\maven\lib\maven3\bin`
