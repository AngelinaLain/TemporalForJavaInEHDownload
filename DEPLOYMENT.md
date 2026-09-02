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
| SYNOLOGY_* 或 SMB_* | 群晖上传参数 |
| NOTIFICATION_ADMIN_EMAIL、GRAPH_* | 汇总邮件参数，不启用邮件时可留空 |
| AI_BASE_URL、AI_API_KEY、AI_MODEL | AI 服务参数，不启用 AI 时按项目要求配置 |

Compose 内的服务不能用 localhost 访问宿主机服务。若 MySQL、Nacos、Temporal 或 Komga 在 10.10.10.175 上运行，应填写 10.10.10.175；如果 Redis 使用 Compose 自带容器，建议设置：

~~~dotenv
REDIS_HOST=redis
REDIS_PORT=6379
DOWNLOAD_MODE=local
DOWNLOAD_TEMP_DIR=/data/download-cache
KOMGA_IMPORT_MAX_RETRIES=40
KOMGA_IMPORT_POLL_INTERVAL_SECONDS=15
~~~

生成 BCrypt 管理员密码哈希的一种方式：

~~~bash
docker run --rm httpd:2.4-alpine htpasswd -bnBC 12 '' '请替换为管理员密码' | cut -d: -f2-
~~~

将输出写入 .env 的 ADMIN_PASSWORD_HASH。密码中若包含特殊字符，编辑 .env 时要注意转义；不要把真实密码写入命令历史或聊天记录。

校验 Compose 配置：

~~~bash
docker compose config --quiet
~~~

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

应用启动时会自动执行 Flyway 数据库迁移，包括当前的 V9__add_komga_confirmation_tracking.sql。不要手工重复执行迁移 SQL。

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

1. /dashboard 能读取数据库统计。
2. /galleries 能显示画廊和下载进度。
3. /komga-import-reviews 能打开 Komga 入库复核页面。
4. 发起一个小画廊测试，确认本地下载、ComicInfo.xml 注入、群晖上传和 Komga 扫描链路正常。
5. 检查 eh-backend 日志中 Flyway、Temporal Worker 和 Nacos 注册均无错误。

Komga 扫描是异步的，确认命中可能需要数分钟。默认每 15 秒轮询一次，最多 40 次；失败记录可在“Komga 入库复核”页面执行“仅重试 Komga”，不会重新下载文件。

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
- MySQL 数据库，尤其是 eh_galleries、eh_dedupe_reviews 和迁移历史表。
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
