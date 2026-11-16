$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    if (-not (Test-Path -LiteralPath '.env')) {
        Copy-Item -LiteralPath '.env.example' -Destination '.env'
        throw '已创建 .env，请填写数据库、Temporal、EH、群晖与 Komga 配置后重新运行。'
    }
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 配置校验失败' }
    docker compose pull rsshub redis
    if ($LASTEXITCODE -ne 0) { throw 'RSSHub / Redis 镜像拉取失败' }
    docker compose --progress plain build backend scraper-worker ai-service frontend
    if ($LASTEXITCODE -ne 0) { throw '应用镜像构建失败' }
    docker compose up -d redis rsshub backend scraper-worker ai-service frontend
    if ($LASTEXITCODE -ne 0) { throw '服务启动失败' }
    docker compose ps
} finally { Pop-Location }
