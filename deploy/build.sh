#!/usr/bin/env bash
# 서버 jar와 화면(서버 모드)을 빌드하고 서버 이미지를 만든다
set -euo pipefail
cd "$(dirname "$0")/.."
(cd backend && ./gradlew -q clean bootJar)
# 약관·개인정보처리방침의 운영자 이름과 연락처는 deploy/.env에서 읽어 화면에 넣는다(저장소에는 넣지 않는다)
env_value() { if [ -f deploy/.env ]; then sed -n "s/^$1=//p" deploy/.env | tail -n 1 | sed 's/^"\(.*\)"$/\1/'; fi; }
VITE_OPERATOR_NAME="$(env_value OPERATOR_NAME)"
VITE_OPERATOR_EMAIL="$(env_value OPERATOR_EMAIL)"
export VITE_OPERATOR_NAME VITE_OPERATOR_EMAIL
(cd frontend && npm ci --silent && VITE_BACKEND=server npm run build)
# compose의 backend 이미지 이름과 같다. compose 파일을 거치지 않아 .env 없이도 빌드된다
docker build -t teamtrading-backend backend
