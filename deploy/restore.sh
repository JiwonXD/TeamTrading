#!/usr/bin/env bash
# 백업 덤프로 DB를 되돌린다. 덤프 이후의 주문·체결·포인트는 모두 사라진다.
#   deploy/restore.sh deploy/backups/teamtrading-20261102-040000.sql.gz
# 서버를 멈추고 DB를 비운 뒤 덤프를 넣고 서버를 다시 띄운다. 서버는 기동할 때 DB에서 호가창을 다시 쌓는다.
set -euo pipefail
cd "$(dirname "$0")"

file=${1:?복원할 덤프 파일을 주세요 (backups/teamtrading-*.sql.gz)}
[ -f "$file" ] || { echo "없는 파일: $file" >&2; exit 1; }
zcat "$file" | tail -n 1 | grep -q "Dump completed" || { echo "끝까지 쓰이지 않은 덤프예요: $file" >&2; exit 1; }

if [ "${2:-}" != --yes ]; then
  echo "$file 시점으로 되돌립니다. 그 뒤의 거래 기록은 모두 사라져요."
  read -r -p "계속하려면 '복원'이라고 입력하세요: " answer
  [ "$answer" = 복원 ] || { echo "취소했어요"; exit 1; }
fi

root_sql() { docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$@"' sh "$@"; }

docker compose stop backend
root_sql -e "DROP DATABASE IF EXISTS teamtrading; CREATE DATABASE teamtrading; GRANT ALL ON teamtrading.* TO 'teamtrading'@'%';"
zcat "$file" | root_sql teamtrading
docker compose up -d --wait backend
echo "복원했어요: $file"
