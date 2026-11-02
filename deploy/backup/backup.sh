#!/usr/bin/env bash
# MySQL 덤프를 /backups에 남긴다. compose의 backup 서비스가 mysql 이미지 안에서 돌린다.
#   backup.sh        매일 BACKUP_AT(한국 시간)에 덤프하고, BACKUP_KEEP_DAYS일 지난 덤프를 지운다.
#                    최근 24시간 안의 덤프가 없으면 시작하자마자 한 번 뜬다
#   backup.sh once   지금 한 번 뜬다
# --single-transaction이라 서비스를 멈추지 않고 한 시점의 장부(InnoDB)를 그대로 뜬다.
# 끝까지 쓰인 덤프("Dump completed")만 이름을 붙여 남기므로, 중간에 실패한 파일이 복원 대상이 되지 않는다.
set -euo pipefail

DIR=/backups
AT=${BACKUP_AT:-04:00}
KEEP=${BACKUP_KEEP_DAYS:-14}
export TZ=Asia/Seoul

dump() {
  local name tmp
  name="teamtrading-$(date +%Y%m%d-%H%M%S).sql.gz"
  tmp="$DIR/.$name.part"
  if mysqldump -h mysql -uroot --single-transaction --routines --triggers --no-tablespaces teamtrading \
      | gzip > "$tmp" && zcat "$tmp" | tail -n 1 | grep -q "Dump completed"; then
    mv "$tmp" "$DIR/$name"
    find "$DIR" -name 'teamtrading-*.sql.gz' -mtime +"$KEEP" -delete
    echo "$(date '+%F %T') backup ok: $name ($(du -h "$DIR/$name" | cut -f1))"
  else
    rm -f "$tmp"
    echo "$(date '+%F %T') backup FAILED" >&2
    return 1
  fi
}

# MySQL이 막 떴거나 잠깐 끊겼을 수 있으므로 10초 간격으로 5분까지 다시 해 본다
dump_retry() {
  for _ in $(seq 30); do
    dump && return 0
    sleep 10
  done
  return 1
}

if [ "${1:-}" = once ]; then
  dump
  exit
fi

if ! find "$DIR" -name 'teamtrading-*.sql.gz' -mmin -1440 | grep -q .; then dump_retry || true; fi
while true; do
  now=$(date +%s)
  next=$(date -d "today $AT" +%s)
  [ "$next" -gt "$now" ] || next=$(date -d "tomorrow $AT" +%s)
  sleep $((next - now))
  dump_retry || true
done
