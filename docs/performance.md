# 성능 측정

주문 API에 부하를 걸어 처리량과 지연을 재고, 끝난 뒤에도 장부가 맞는지 확인한다.

## 도구

`LoadTest`(`backend/src/test/java/com/teamtrading/load`)는 일반 테스트에서 빠지고 `loadTest` 작업으로만 돈다.

```bash
cd backend
docker compose up -d
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/teamtrading?useLocalSessionState=true&rewriteBatchedStatements=true&cachePrepStmts=true&useServerPrepStmts=true&prepStmtCacheSize=250' \
  ./gradlew loadTest -Pload.users=50 -Pload.seconds=20 -Pload.teams=3
cat build/reports/load/summary.txt
```

동작 방식:

- 사용자마다 개발용 로그인 세션을 하나 두고, 구단마다 200주를 지급한다.
- 정해진 시간 동안 기준가 ±3호가 지정가 주문(1~3주)을 쉬지 않고 보낸다. 열 번에 한 번은 대기 주문을 취소한다.
- 끝나면 다음을 확인한다. 하나라도 어긋나면 실패다.
  - 5xx·연결 오류 0건
  - `PENDING` 주문 0건
  - 묶인 포인트 합 = 대기 매수 주문 예치 합
  - 호가가 교차하지 않음
  - 원장 대사 일치
- 지연과 처리량은 실행 환경에 따라 달라서 기준을 두지 않고 기록만 한다.

CI의 MySQL 작업은 20명 × 15초로 매번 돌리고, 결과를 작업 요약에 남긴다.

## 결과 (2026-11-02)

환경: 4 vCPU 컨테이너 하나에서 부하 클라이언트, 서버, MySQL 8.4(docker, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`)를 함께 돌렸다. 측정 중 load average가 10까지 올라 CPU가 포화됐다. 그래서 절대값은 하한으로 보고, 같은 환경에서 나란히 비교한 값만 의미가 있다.

| 조건 | 처리량 | p50 | p95 | p99 | 정합성 |
| --- | --- | --- | --- | --- | --- |
| 1명, 3구단 | 36~57 주문/초 | 15~23ms | 26~34ms | 37~52ms | 통과 |
| 50명, 3구단, 드라이버 기본값 | 94~113 주문/초 | 387~450ms | 709~918ms | 770~1,103ms | 통과 |
| 50명, 3구단, 드라이버 설정 후 | 132~139 주문/초 | 315~358ms | 568~581ms | 647~654ms | 통과 |
| 50명, 10구단, 드라이버 기본값 | 134 주문/초 | 294ms | 728ms | 982ms | 통과 |
| 50명, 10구단, 드라이버 설정 후 | 155 주문/초 | 235ms | 689ms | 910ms | 통과 |

모든 실행에서 5xx·연결 오류는 0건이었고 대사가 맞았다.

## 병목

JFR로 보면 50명 부하에서 요청 스레드 대기 시간의 대부분이 종목 락(`TradingService.place`의 `market.lock.lock()`) 대기다. CPU 실행 샘플은 적었다. 종목 락을 잡고 있는 동안 걸린 시간을 임시 계측으로 나누면 다음과 같다(50명, 3구단, 주문당 평균).

| 구간 | 드라이버 기본값 | 드라이버 설정 후 |
| --- | --- | --- |
| 검증·멱등키 조회 | 1.2ms | 1.0ms |
| 접수 트랜잭션 (주문 저장, 예치) | 10.5ms | 8.5ms |
| 매칭 (메모리) | 0.1ms 미만 | 0.1ms 미만 |
| 정산 트랜잭션 (체결, 잔고, 원장) | 10.4ms | 8.6ms |
| 시세 발행 | 0.2ms | 0.2ms |
| 합계 | 22.5ms | 18.5ms |

한 구단의 주문은 이 구간을 하나씩 지나가므로 구단당 처리량의 상한은 약 1 / 18.5ms ≈ 54 주문/초다. 락 안 시간의 90% 이상이 DB 트랜잭션 두 번이다. `performance_schema`로 보면 주문 하나에 문장이 약 20개 나가고, 그중 COMMIT 두 번이 평균 2.4ms씩으로 가장 크다.

## 적용한 개선

DB URL에 MySQL 드라이버 설정을 넣었다. 동작과 트랜잭션 경계는 바뀌지 않는다.

- `useLocalSessionState=true`: 트랜잭션마다 나가던 `SET autocommit`, `SELECT @@session.transaction_read_only` 왕복을 줄인다.
- `rewriteBatchedStatements=true`: 원장 분개(주문당 약 5행)를 한 번의 다중 행 INSERT로 보낸다.
- `cachePrepStmts`, `useServerPrepStmts`, `prepStmtCacheSize=250`: 서버 측 준비문을 연결마다 캐시한다.

## 다음 후보

효과가 크지만 설계를 바꾸는 일이라 측정 근거만 남겨 둔다.

1. **주문당 트랜잭션 하나로 합치기.** 지금은 접수와 정산을 따로 커밋한다(ADR 0003). 합치면 락 안 커밋이 절반이 된다. 대신 정산 실패 시 접수 기록도 함께 사라지므로 실패 처리와 기동 복구를 다시 설계해야 한다.
2. **구단별 묶음 커밋.** 락 안에서 여러 주문을 매칭하고 한 트랜잭션으로 정산한다. 커밋 수가 묶음 크기만큼 줄지만, 응답은 묶음 커밋 뒤에 보내야 한다.
3. **실제 서버에서 다시 재기.** 부하 클라이언트를 다른 장비로 빼고, 배포할 DB(예: RDS gp3)에서 커밋 지연을 따로 잰다.
