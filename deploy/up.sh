#!/usr/bin/env bash
# One-command application deployment (MySQL, Temporal and Nacos remain externally configured).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ ! -f .env ]]; then
  cp .env.example .env
  echo '已创建 .env，请填写数据库、Temporal、EH、群晖与 Komga 配置后重新运行。'
  exit 1
fi
docker compose config --quiet
docker compose pull rsshub redis
docker compose --progress plain build backend scraper-worker ai-service frontend
docker compose up -d redis rsshub backend scraper-worker ai-service frontend
docker compose ps
