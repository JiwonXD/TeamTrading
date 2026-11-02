#!/usr/bin/env bash
# 서버 jar와 화면(서버 모드)을 빌드하고 서버 이미지를 만든다
set -euo pipefail
cd "$(dirname "$0")/.."
(cd backend && ./gradlew -q clean bootJar)
(cd frontend && npm ci --silent && VITE_BACKEND=server npm run build)
# compose의 backend 이미지 이름과 같다. compose 파일을 거치지 않아 .env 없이도 빌드된다
docker build -t teamtrading-backend backend
