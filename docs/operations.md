# 운영 점검

서버 상태와 정합성을 무엇으로 보고, 어긋나면 무엇을 하는지 적는다.

## 관리 포트

운영 지표는 서비스 포트(8080)와 분리된 관리 포트(8081)에서만 연다. 서비스 포트의 `/actuator/**`는 404다. 관리 포트는 외부에 열지 않고 Prometheus와 로드밸런서 헬스 체크만 닿게 둔다.

| 경로 | 용도 |
| --- | --- |
| `/actuator/health/liveness` | 프로세스가 살아 있는지. 실패하면 재시작 |
| `/actuator/health/readiness` | 요청을 받아도 되는지. 실패하면 트래픽에서 뺀다 |
| `/actuator/prometheus` | Prometheus 스크레이프 |

로컬 확인:

```bash
SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun
curl -s localhost:8081/actuator/health
curl -s localhost:8081/actuator/prometheus | grep '^teamtrading_'
```

## 지표

| 지표 | 종류 | 뜻 |
| --- | --- | --- |
| `teamtrading_reconciliation_balanced` | 게이지 | 마지막 대사가 맞았으면 1, 어긋났거나 대사를 못 돌렸으면 0. 첫 대사 전에는 1 |
| `teamtrading_reconciliation_mismatches` | 게이지 | 마지막 대사에서 어긋난 칸·전표 수 |
| `teamtrading_reconciliation_last_run` | 게이지 | 마지막 대사 시각(epoch 초). 첫 대사 전에는 기동 시각 |
| `teamtrading_reconciliation_duration_seconds` | 타이머 | 대사 소요 시간 |
| `teamtrading_trades_total{team}` | 카운터 | 체결 건수 |
| `teamtrading_trade_shares_total{team}` | 카운터 | 체결 수량 |
| `teamtrading_trade_points_total{team}` | 카운터 | 체결 대금 |
| `teamtrading_markets_halted` | 게이지 | 호가창 복구에 실패해 거래를 멈춘 구단 수 |
| `teamtrading_feed_sessions` | 게이지 | 실시간 시세 구독 세션 수 |
| `teamtrading_feed_dropped_total` | 카운터 | 큐가 넘쳐 끊은 느린 세션 수 |
| `http_server_requests_seconds` | 히스토그램 | API 지연. SLO 구간 50ms·100ms·250ms·500ms·1s |

대사는 기동 1분 뒤부터 5분마다 돈다(`teamtrading.monitoring.reconcile-every`). 운영자는 `GET /api/surveillance/reconciliation`으로 언제든 직접 돌려 볼 수 있다.

## 경보 규칙

```yaml
groups:
  - name: teamtrading
    rules:
      - alert: LedgerOutOfBalance
        expr: teamtrading_reconciliation_balanced == 0
        labels: { severity: critical }
        annotations:
          summary: 원장과 잔고가 어긋났다
      - alert: ReconciliationStale
        expr: time() - teamtrading_reconciliation_last_run > 900
        labels: { severity: warning }
        annotations:
          summary: 대사가 15분 넘게 돌지 않았다
      - alert: MarketHalted
        expr: teamtrading_markets_halted > 0
        labels: { severity: critical }
        annotations:
          summary: 거래가 멈춘 구단이 있다
      - alert: SlowOrders
        expr: |
          histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/orders",method="POST"}[5m]))) > 0.25
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: 주문 p99 지연이 250ms를 넘었다
```

## 경보가 울리면

**LedgerOutOfBalance**

1. 로그에서 `reconciliation failed`를 찾는다. 어긋난 계정·자산·칸과 합이 0이 아닌 전표가 함께 찍힌다.
2. 그 계정의 최근 전표(`ledger_journals`)와 주문·체결을 시간순으로 맞춰 본다.
3. 원인을 고치기 전까지 잔고를 손으로 맞추지 않는다. 고칠 때도 잔고를 직접 바꾸지 말고 보정 전표를 남긴다.

**MarketHalted**

1. 로그에서 그 구단의 복구 실패 원인을 찾는다.
2. 원인을 고친 뒤 서버를 다시 띄우면 기동 복구가 호가창을 DB에서 다시 쌓는다.
