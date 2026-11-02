#!/usr/bin/env bash
# 서버 jar와 화면(서버 모드)을 빌드하고 서버 이미지를 만든다
set -euo pipefail
cd "$(dirname "$0")/.."
(cd backend && ./gradlew -q clean bootJar)
(cd frontend && npm ci --silent && VITE_BACKEND=server npm run build)
docker compose -f deploy/docker-compose.yml build backend
