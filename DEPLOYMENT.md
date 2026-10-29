# GalleryImport 部署文档

本文以 Linux Docker 主机 10.10.10.175 为例，说明从 GitHub 部署当前 main 分支的完整流程。文档中的密码、Cookie、API Key 和数据库凭据只写入服务器上的 .env，不要提交到 Git。

## 1. 部署架构

docker-compose.yml 会启动以下容器：

| 容器 | 端口 | 作用 |
| --- | ---: | --- |
| eh-backend | 8001 | Spring Boot API、Temporal Worker、下载和 Komga 入库确认 |
| eh-scraper-worker | 8081 | EHentai 抓取 Worker |
| eh-ai-service | 8082 | AI 摘要/翻译服务 |
| eh-frontend | 8002 | Vue 管理页面和 Nginx API 反向代理 |
| eh-redis | 6379 | JWT 黑名单和 AI 缓存 |

MySQL、Temporal、Nacos、Synology 和 Komga 由外部环境提供，不由本 Compose 文件创建。

默认会创建三个持久卷：

- download-cache：本地下载断点文件和失败重试缓存。
- redis-data：Redis AOF 数据。
- grafana-data：可选 Grafana 数据。

不要使用 docker compose down -v，否则会删除这些卷。

## 2. 主机准备

登录服务器：

~~~bash
ssh angelina@10.10.10.175
~~~

确认 Docker、Compose 和 Git 可用：

~~~bash
docker --version
docker compose version
git --version
~~~

创建部署目录并获取代码：

~~~bash
sudo mkdir -p /opt
sudo chown -R "$USER":"$USER" /opt
cd /opt

if [ -d GalleryImport/.git ]; then
  cd GalleryImport
  git fetch origin
  git switch main
  git pull --ff-only origin main
else
  git clone https://github.com/AngelinaLain/TemporalForJavaInEHDownload.git GalleryImport
  cd GalleryImport
fi
~~~

如果服务器上已有旧版容器，先查看其来源，确认不要影响其他项目：

~~~bash
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker inspect eh-backend --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true
~~~

如果旧容器就是本项目的 Compose 容器，升级时使用 docker compose down --remove-orphans 即可；该命令不会删除卷。若旧容器不是当前 Compose 创建的，必须先确认后再停止同名的旧容器，否则新容器会因名称或端口冲突无法启动。

## 3. 配置 .env

首次部署：

~~~bash
cd /opt/GalleryImport
cp .env.example .env
chmod 600 .env
nano .env
~~~

至少填写以下配置：

| 配置 | 说明 |
| --- | --- |
| NACOS_SERVER_ADDR | Nacos 地址，例如 10.10.10.175:8848 |
| DB_HOST、DB_PORT、DB_NAME、DB_USERNAME、DB_PASSWORD | MySQL 连接信息 |
| TEMPORAL_HOST、TEMPORAL_PORT | Temporal 地址，默认端口 7233 |
| JWT_SECRET | 至少 32 字节的随机字符串 |
| ADMIN_USERNAME、ADMIN_PASSWORD_HASH | 管理员账号和 BCrypt 密码哈希 |
| EH_MEMBER_ID、EH_PASS_HASH、EH_SK、EH_STAR | EHentai Cookie 参数 |
| KOMGA_URL、KOMGA_API_KEY、KOMGA_LIBRARY_ID | Komga 连接信息 |
| KOMGA_PUBLIC_URL（可选） | 浏览器点击“一键打开 Komga”时使用的局域网或反代地址；留空时使用 KOMGA_URL |
| SYNOLOGY_* 或 SMB_* | 群晖上传参数 |
| NOTIFICATION_ADMIN_EMAIL、GRAPH_* | 汇总邮件参数，不启用邮件时可留空 |
| AI_BASE_URL、AI_API_KEY、AI_MODEL | ai-service 调用 OpenAI 兼容服务的兜底配置 |
| AI_CONFIG_MASTER_KEY | 加密数据库内供应商 API Key，至少 16 个字符，勿与 JWT_SECRET 复用 |
| AI_INTERNAL_TOKEN | main-service 调用 ai-service 内部接口的令牌，至少 16 个字符 |
| AI_EMBEDDING_* | 本地视觉向量模型，启用 AI 视觉复核时才需要 |

Compose 内的服务不能用 localhost 访问宿主机服务。若 MySQL、Nacos、Temporal 或 Komga 在 10.10.10.175 上运行，应填写 10.10.10.175；如果 Redis 使用 Compose 自带容器，建议设置：

~~~dotenv
REDIS_HOST=redis
REDIS_PORT=6379
DOWNLOAD_MODE=local
DOWNLOAD_TEMP_DIR=/data/download-cache
KOMGA_IMPORT_MAX_RETRIES=40
KOMGA_IMPORT_POLL_INTERVAL_SECONDS=15
~~~

`KOMGA_URL` 供后端请求 Komga API，`KOMGA_PUBLIC_URL` 供用户浏览器跳转；两者可以不同。例如后端使用 Docker 服务名，而浏览器使用主机地址：

~~~dotenv
KOMGA_URL=http://komga:25600
KOMGA_PUBLIC_URL=http://10.10.10.175:25600
~~~

反向代理部署在子路径时，可设置为 `https://reader.example.com/komga`。不要在这两个 URL 中写入用户名、密码或 API Key；服务端凭据单独保存在 `KOMGA_API_KEY`，不会添加到浏览器跳转链接中。链接使用已记录的 `komgaBookId`，浏览器仍需登录 Komga 或具有对应的访问权限。

生成 BCrypt 管理员密码哈希的一种方式：

~~~bash
docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 '' '请替换为管理员密码' | cut -d: -f2-
~~~

将输出写入 .env 的 ADMIN_PASSWORD_HASH。密码中若包含特殊字符，编辑 .env 时要注意转义；不要把真实密码写入命令历史或聊天记录。

登录后的账号菜单支持“修改密码”，需验证当前密码。V22 将新 BCrypt 哈希及凭据版本保存在数据库，改密后所有旧会话失效，需要重新登录。`ADMIN_PASSWORD_HASH` 仅作为没有数据库覆盖记录时的初始凭据；修改环境变量或重启不会覆盖已修改的密码。凭据表属于持久数据库数据，备份时应与业务表一起保留。账号存储不可用时认证失败关闭，不会退回初始密码。

全局字体可从顶部 `Aa` 按钮、账号菜单的“字体与显示”或侧栏底部“显示与账户设置”调整。提供小号（90%）、标准（100%）、大号（115%）、特大（130%）四档，立即应用，可恢复标准；偏好保存在当前浏览器，刷新后继续使用，不会修改服务端配置。表格、输入框、弹层和系统内图表同步调整文字；嵌入的 Grafana 等独立监控页面保留各自字号。

校验 Compose 配置：

~~~bash
docker compose config --quiet
~~~

### Docker 直接挂载 Komga Library（推荐）

如果 Docker 主机本身就是群晖，或已经通过 NFS/CIFS 挂载了群晖目录，可让后端直接读写该目录，避免 SMBJ/SFTP 连接开销。`ARCHIVE_HOST_PATH` 必须指向 Komga 当前 Library 的根目录（也就是现有 `N8N_Update` CBZ 所在目录），例如：

~~~dotenv
ARCHIVE_HOST_PATH=/volume1/n8n_bot/EHentai
~~~

若 Docker 运行在另一台 Linux 主机，可先把群晖目录挂载到例如 `/mnt/komga-library`，再设置：

~~~dotenv
ARCHIVE_HOST_PATH=/mnt/komga-library
~~~

使用挂载覆盖文件启动：

~~~bash
docker compose -f docker-compose.yml -f docker-compose.mount.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.mount.yml up -d --build --remove-orphans
docker compose exec backend sh -c 'test -r /data/komga-library && test -w /data/komga-library'
~~~

容器内固定使用 `/data/komga-library`。路径可读写时应用自动优先使用本地文件系统；没有使用该覆盖文件或挂载不可用时，继续回退到 SMB/SFTP。Komga 与后端必须挂载同一个实际目录。

画廊图片预览优先读取已挂载的本地 CBZ/ZIP，其次读取已关联的 Komga 图库，封面采用相同策略。预览不会访问 EH 源站，因此源站画廊丢失不会影响已有本地图片的浏览。上面的 SMB/SFTP 回退适用于原有归档读写流程；预览不会为了翻页从 SMB/SFTP 复制整本归档。没有可读归档且 Komga 未关联或不可用时，界面会提示先完成入库，或检查归档挂载与 Komga 书籍关联。

已入库但预览不可用时，先查看预览面板的“本地图片检测结果”。归档移动到其他系列目录后，预览会使用短缓存的 GID 目录索引重新定位唯一文件；若 Komga 重扫替换了 Book ID，则按 GID／标题词召回候选，并用物理文件名或 URL 中的 GID／一致文件名确认，不只凭相似标题匹配，也不会自动修改数据库。多个候选、挂载权限、Komga 凭据或书籍分析等问题会分别显示诊断。修复配置或移动文件后，点击始终可见的“重新检测本地图片”按钮，清除该画廊的预览元数据与来源缓存，并重建本地定位索引；此操作只读，不重新下载图片或改写归档。

### AI 视觉复核（可选）

`ai-service` 默认不加载模型文件。启用 AI 视觉复核需要两步：

1. 确认仓库 `models/` 目录存在视觉模型（默认 `dinov2-small-ONNX_int8.onnx`）。Compose 已把 `./models` 只读挂载到容器内 `/models`。
2. 在 `.env` 中补充：

~~~dotenv
AI_EMBEDDING_ENABLED=true
AI_EMBEDDING_MODEL_PATH=/models/dinov2-small-ONNX_int8.onnx
AI_EMBEDDING_MODEL_VERSION=dinov2-small-int8-v1
AI_CONFIG_MASTER_KEY=<至少 16 个字符的随机字符串>
AI_INTERNAL_TOKEN=<至少 16 个字符的随机字符串>
~~~

`AI_INTERNAL_TOKEN` 少于 16 个字符时，ai-service 会对向量与动态模型接口直接返回 503，视觉复核任务必然失败。更换 ONNX 权重或预处理方式后必须同时修改 `AI_EMBEDDING_MODEL_VERSION`，旧向量会自动失效并重算。

部署后可在“AI 设置”页添加 OpenAI 兼容供应商（例如本地 Ollama 填 `http://ollama:11434`；容器内不能用 localhost 访问宿主机服务），并为“视觉重复复核”选择分析模式。默认的 `EMBEDDING_ONLY` 只做本地向量与感知哈希比对，不需要 LLM。AI 结论仅作为人工审核证据，不会自动提交去重结论。

## 4. 首次启动和升级

先构建镜像，再启动容器：

~~~bash
cd /opt/GalleryImport
docker compose build --pull
docker compose up -d --remove-orphans
~~~

后续升级通常只需：

~~~bash
cd /opt/GalleryImport
git fetch origin
git switch main
git pull --ff-only origin main
docker compose build --pull
docker compose up -d --remove-orphans
~~~

应用启动时会自动执行 Flyway 数据库迁移，当前已推进到 V23__add_dedupe_settings.sql。不要手工重复执行迁移 SQL。

### 去重策略设置

升级前后端后，在“去重审核 → 去重参数”调整人工审核起点、自动同作起点、标题近似门槛和视觉证据条件。默认分别为 65 分、85 分、88%，视觉默认容差 18、至少匹配 3 页、覆盖率 70%、画质分差 3%。保存到数据库后无需重启，多实例在下一次业务操作读取新值；同一次操作使用固定参数快照。

相同作品键仍需比较实际元数据。明确章节编号冲突或页数至少相差一倍的候选会暂缓自动合并，人工确认的结论继续优先。参数保存不会批量改写历史去重关系。调参后可点击审核卡片的“更新视觉证据”，按新条件重比已有指纹，无需强制重算归档；视觉证据不会单独自动合并画廊。AI 向量分析的模型及阈值继续在“AI 设置”管理。

### 本次画廊检索、图片预览和数据概览更新

本次功能涉及前端和后端，只有 `git pull` 或重启旧容器不会更新已构建镜像。在本地变更已经提交并推送到远端、服务器也已拉取对应提交后，可只重建这两个服务：

~~~bash
docker compose build backend frontend
docker compose up -d --no-deps backend frontend
~~~

如果当前使用归档挂载覆盖文件，更新时也要保留相同的 Compose 文件组合，避免重建后丢失挂载：

~~~bash
docker compose -f docker-compose.yml -f docker-compose.mount.yml build backend frontend
docker compose -f docker-compose.yml -f docker-compose.mount.yml up -d --no-deps backend frontend
~~~

本次增加的 `KOMGA_PUBLIC_URL` 是可选配置，不改变 Temporal 工作流载荷类型；这组检索/预览/概览改动不要求同步重建其余 Java Worker。V21 为画廊列表新增 `(crawled_at,gid)` 与 `(duplicate_of_gid,crawled_at,gid)` 索引，迁移通过 `information_schema` 检查避免重复创建；首次升级的大表建索引可能耗时，应观察后端 Flyway 日志等待启动完成。通用升级仍可使用上文的全服务构建。容器更新完成后用浏览器强制刷新页面。

查看启动状态：

~~~bash
docker compose ps
docker compose logs --tail=200 backend
docker compose logs --tail=100 frontend
~~~

## 5. 部署验收

在服务器上执行：

~~~bash
curl -fsS http://127.0.0.1:8001/actuator/health
curl -I http://127.0.0.1:8002/
~~~

浏览器访问：

~~~text
http://10.10.10.175:8002
~~~

使用 .env 中的管理员账号登录。登录后重点检查：

1. /dashboard 能读取多维统计，按时间、状态、版本、合集筛选，日/周/月时间线及图表点击钻取能保留对应查询范围。
2. /galleries 能组合关键词、标签、评分/页数/体积和合集条件检索；列表显示“合集/独立画廊”及合集名称。该类型表示本地归组关系，不是内容题材。
3. /komga-import-reviews 能打开 Komga 入库复核页面。
4. /ai-settings 能加载供应商与用例列表（新增 AI 配置时重点检查）。
5. 发起一个小画廊测试，确认本地下载、ComicInfo.xml 注入、群晖上传和 Komga 扫描链路正常。
6. 检查 eh-backend 日志中 Flyway、Temporal Worker 和 Nacos 注册均无错误。
7. 需要接口调试时访问 http://10.10.10.175:8001/swagger-ui.html。
8. /tag-network 的“标签数量”可选择“全部标签 · 按需探索”；确认加载进度、取消、分类筛选和侧栏分页可用，点击标签后才请求关联，关联数超过 100 时提示实际总数。
9. 顶部 `Aa` 调整字体后，检查表格、筛选输入框与设置弹层同步变化，刷新仍保留选择；账号菜单修改密码成功后重新登录。

Komga 扫描是异步的，确认命中可能需要数分钟。默认每 15 秒轮询一次，最多 40 次；失败记录可在“Komga 入库复核”页面执行“仅重试 Komga”，不会重新下载文件。

图片预览可在有可读本地归档或已关联 Komga 的画廊上检查：列表首次加载不应批量请求封面；停留在一行满 1 秒后出现封面与 GID、评分等信息；点击标题后可按 `10/20/50/100` 张分页，可视区域内的缩略图才加载，点击单张再读取原图；关闭预览后停止未完成加载。即使 EH 源站画廊已经丢失，本地预览仍应可用；本地没有图片时应显示说明，不自动请求源站。已关联 Komga 的记录应能跳转到浏览器可访问的书籍页面。

悬停封面或其他后台图片失败时，应保留当前页面与登录凭据；缺图或 Komga 权限问题不等于系统账号登出。服务端媒体错误应保持 `404/502` 等原状态，正常业务接口返回真正的 `401` 时仍应要求重新登录。预览没有图片时检查诊断列表及“重新检测本地图片”按钮，再确认图库挂载和 Komga API Key，而非反复重新登录。

预览使用前端最多 4 个图片请求、后端最多 6 个并发操作，并限制单张原图为 25 MiB、缩略图最长边为 300px。缩略图和图片源索引采用有界缓存；概览统计同一筛选组合缓存 30 秒。本地存储读取或 Komga 响应速度仍会影响实际等待时间；预览并发已满时 HTTP `429` 可稍后重试。

全部标签由 `/api/dashboard/tag-network?limit=0&offset=0&pageSize=500` 首包创建固定 5 分钟的目录快照。后续页同时传回 `snapshotId` 和 `nextOffset`，保持作品关键词不变，直到 `hasMore=false`；顺序和总数在快照内固定，分页不会续期。缺少快照、过期、被逐出或关键词不符会返回业务 `400` 并要求重新加载，前端会校验分页完整性。单快照估算预算 32 MiB，总快照预算 64 MiB；超过单快照预算会明确提示缩小作品标题范围，不会静默截断标签。

前端采用 Canvas 与视口裁剪，侧栏每页 50 项；点击标签后通过 `/api/dashboard/tag-network/neighbors` 读取直接关联，默认最多返回 100 个并保留 `totalConnections/truncated` 信息。高频网络和按需关联缓存 30 秒，不预先计算全部标签的两两连接。标签很多时初次聚合和传输仍需要时间，可先按作品标题收窄范围；取消加载会保留上一次成功的图谱。

### 刷新数据库中的 Komga 关联 ID

在“Komga 入库复核”页面上方使用“刷新数据库中的 Komga ID”。可输入 `1553036` 等指定 GID（最多 1000 个），留空则检查所有已有关联或已入库的记录；点击“刷新失效 ID”启动后台任务，查看进度、已更新/有效/未找到/多个候选/冲突/失败计数及新旧 ID 明细，也可停止任务。有效关联不逐条列出，变更与异常明细最多显示 200 条，计数仍包含全部结果。

功能使用当前 `KOMGA_URL`、`KOMGA_API_KEY` 和可选 `KOMGA_LIBRARY_ID`，先读取完整物理文件索引，再仅将失效或缺失且唯一匹配的 ID 写回数据库；未找到和歧义保留原值。不会删除文件、修改下载状态、重新下载或请求源站。更新使用原 ID、文件名及目录条件防止覆盖其他任务的并发变更，并清除相应画廊的预览缓存。读取索引失败或不完整时不开始写库；任务中途停止或失败时，已经提交的更新会保留。

此功能无新增数据库迁移；部署需重建前端与后端。任务在用户点击后启动，升级不会自动批量修改关联。当前实例只允许一个任务，进度保存在进程中，重启后清除；数据库中已完成的更新持久保留。

## 6. 可选监控组件

启动 Prometheus、Grafana、Loki、Promtail 和 Zipkin：

~~~bash
docker compose --profile observability up -d
~~~

访问地址：

| 组件 | 地址 |
| --- | --- |
| Grafana | http://10.10.10.175:3000 |
| Prometheus | http://10.10.10.175:9090 |
| Loki | http://10.10.10.175:3100 |
| Zipkin | http://10.10.10.175:9411 |

## 7. 持久卷与备份

查看卷：

~~~bash
docker volume ls | grep -E 'download-cache|redis-data|grafana-data'
~~~

下载缓存上传成功后会删除对应 GID 的工作目录；上传失败或重试时会保留缓存。升级时不要删除 download-cache 卷，否则会失去断点续传和失败重试数据。

可在维护窗口备份下载缓存：

~~~bash
mkdir -p backups
DOWNLOAD_VOLUME=$(docker volume ls --format '{{.Name}}' | awk '/download-cache$/ {print; exit}')
test -n "$DOWNLOAD_VOLUME"
docker run --rm \
  -v "$DOWNLOAD_VOLUME:/data:ro" \
  -v "$PWD/backups:/backup" \
  alpine tar czf /backup/download-cache-$(date +%Y%m%d-%H%M%S).tar.gz -C /data .
~~~

同时备份：

- /opt/GalleryImport/.env（使用受限权限保存，不要上传公共位置）。
- MySQL 数据库，尤其是 eh_galleries、eh_dedupe_reviews、AI 配置相关表（ai_provider_configs、ai_use_case_configs、ai_prompt_versions）和迁移历史表。
- .env 中的 AI_CONFIG_MASTER_KEY：它丢失后数据库里的供应商 API Key 无法解密，只能重新录入。
- Redis 数据（如果需要保留登录黑名单或 AI 缓存）。

## 8. 常用运维命令

~~~bash
# 查看所有服务状态
docker compose ps

# 实时查看后端日志
docker compose logs -f --tail=200 backend

# 重启单个服务
docker compose restart backend

# 停止服务但保留卷
docker compose stop

# 停止并移除容器、网络，但保留卷
docker compose down --remove-orphans

# 查看资源使用情况
docker stats
~~~

## 9. 常见问题

### 后端反复重启

~~~bash
docker compose logs --tail=300 backend
~~~

优先检查 .env 中 MySQL、Temporal、Nacos、JWT 和管理员密码哈希是否完整；再从容器内确认目标地址和端口可达。

### 前端页面打开但 API 返回 502

确认后端容器运行正常，并检查两个容器在同一个 Compose 网络：

~~~bash
docker compose ps
docker compose logs --tail=100 frontend backend
~~~

前端 Nginx 通过 Compose 服务名 backend:8001 代理 /api/，不要把 Nginx 配置改成宿主机 localhost。

### Komga 长时间没有入库

检查 KOMGA_URL、KOMGA_API_KEY、KOMGA_LIBRARY_ID，确认后端容器可以访问 Komga，并确认目标目录已被 Komga Library 扫描。系统要求文件名精确匹配 [gid] 标题.cbz，目标系列默认是 N8N_Update。失败记录可在 /komga-import-reviews 中查看最近原因和候选 BookID。

### 端口被旧容器占用

~~~bash
docker ps --format 'table {{.Names}}\t{{.Ports}}\t{{.Status}}'
~~~

确认是本项目旧容器后，再执行 docker compose down --remove-orphans 或停止对应旧容器。不要为了释放端口执行 docker compose down -v。

## 10. 回滚

先查看可用提交：

~~~bash
git log --oneline --decorate -10
~~~

在确认目标提交后回滚代码并重建镜像：

~~~bash
git switch --detach <已确认的提交号>
docker compose build --pull
docker compose up -d --remove-orphans
~~~

回滚数据库前必须确认 Flyway 迁移是否可逆；本项目迁移默认只向前兼容，不建议直接删除迁移记录或手工修改 flyway_schema_history。
