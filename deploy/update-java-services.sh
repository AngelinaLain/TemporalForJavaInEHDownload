#!/usr/bin/env bash
# common contains Temporal payload types used by every Java worker. Deploy them together.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
docker compose --progress plain build backend scraper-worker ai-service
docker compose up -d --no-deps backend scraper-worker ai-service
