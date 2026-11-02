#!/usr/bin/env bash
# 장애 테스트: 주문을 받는 중에 서버를 kill -9로 죽이고 다시 띄운 뒤 장부가 맞는지 본다.
#   ROUNDS=3 USERS=20 tools/chaos/run.sh
# 필요: MySQL 8.4(기본은 backend/docker-compose.yml의 컨테이너 backend-mysql-1), Java 21, Node 22.
# 다른 MySQL을 쓰려면 MYSQL_ROOT에 root로 접속하는 명령을 준다. 예: MYSQL_ROOT="mysql -h127.0.0.1 -uroot -proot"
# 매 회차 확인: 응답받은 주문 보존, 응답 못 받은 주문 재시도의 멱등성, PENDING 0건, 묶인 포인트 일치, 원장 대사 일치.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROUNDS=${ROUNDS:-3}
USERS=${USERS:-20}
DB=${DB:-chaostest}
MYSQL_ROOT=${MYSQL_ROOT:-docker exec -i backend-mysql-1 mysql -uroot -proot}
WORK=${WORK:-$(mktemp -d)}
mkdir -p "$WORK"
export MYSQL="$MYSQL_ROOT -N -B $DB"
CLIENT="node tools/chaos/client.mjs"
BASE=http://localhost:8080
LOG=$WORK/orders.jsonl
touch "$LOG"

$MYSQL_ROOT -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB; GRANT ALL ON $DB.* TO 'teamtrading'@'%';" 2>/dev/null
(cd backend && ./gradlew -q bootJar)
JAR=$(ls backend/build/libs/*.jar | grep -v plain | head -1)

PID=
start() {
  java -jar "$JAR" --spring.profiles.active=demo \
    "--spring.datasource.url=jdbc:mysql://localhost:3306/$DB?useLocalSessionState=true&rewriteBatchedStatements=true" \
    "--teamtrading.auth.admins=${ADMIN:-}" >>"$WORK/server.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 90); do
    curl -sf localhost:8081/actuator/health/readiness >/dev/null && return 0
    kill -0 "$PID" 2>/dev/null || { echo "server exited"; tail -50 "$WORK/server.log"; exit 1; }
    sleep 1
  done
  echo "server not ready"; exit 1
}
trap '[ -n "$PID" ] && kill -9 $PID 2>/dev/null || true' EXIT

start
ADMIN=$($CLIENT whoami "$BASE" chaos-admin)
for round in $(seq 1 "$ROUNDS"); do
  $CLIENT load "$BASE" "$USERS" 60 "$LOG" &
  LOAD=$!
  sleep $((4 + RANDOM % 8))
  kill -9 "$PID"
  wait "$LOAD" || true
  echo "round $round: killed server at $(grep -c '"sent"' "$LOG") sent orders," \
    "$($MYSQL -e "SELECT COUNT(*) FROM orders WHERE status = 'PENDING'" 2>/dev/null) pending at crash"
  start
  $CLIENT retry "$BASE" "$LOG"
  $CLIENT check "$BASE" "$LOG"
done
echo "log: $LOG"
