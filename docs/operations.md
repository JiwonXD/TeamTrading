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
curl -s localhost:8081/actuator/prometheus | grep '^fanchart_'
```

## 지표

| 지표 | 종류 | 뜻 |
| --- | --- | --- |
| `fanchart_reconciliation_balanced` | 게이지 | 마지막 대사가 맞았으면 1, 어긋났거나 대사를 못 돌렸으면 0. 첫 대사 전에는 1 |
| `fanchart_reconciliation_mismatches` | 게이지 | 마지막 대사에서 어긋난 칸·전표 수 |
| `fanchart_reconciliation_last_run` | 게이지 | 마지막 대사 시각(epoch 초). 첫 대사 전에는 기동 시각 |
| `fanchart_reconciliation_duration_seconds` | 타이머 | 대사 소요 시간 |
| `fanchart_trades_total{team}` | 카운터 | 체결 건수 |
| `fanchart_trade_shares_total{team}` | 카운터 | 체결 수량 |
| `fanchart_trade_points_total{team}` | 카운터 | 체결 대금 |
| `fanchart_markets_halted` | 게이지 | 호가창 복구에 실패해 거래를 멈춘 구단 수 |
| `fanchart_feed_sessions` | 게이지 | 실시간 시세 구독 세션 수 |
| `fanchart_feed_dropped_total` | 카운터 | 큐가 넘쳐 끊은 느린 세션 수 |
| `http_server_requests_seconds` | 히스토그램 | API 지연. SLO 구간 50ms·100ms·250ms·500ms·1s |

대사는 기동 1분 뒤부터 5분마다 돈다(`fanchart.monitoring.reconcile-every`). 운영자는 `GET /api/surveillance/reconciliation`으로 언제든 직접 돌려 볼 수 있다.

## 경보 규칙

규칙 파일은 [deploy/prometheus/alerts.yml](../deploy/prometheus/alerts.yml)이다. 배포 묶음(docker compose)의 Prometheus가 이 파일을 읽는다.

| 경보 | 조건 | 등급 |
| --- | --- | --- |
| LedgerOutOfBalance | `fanchart_reconciliation_balanced == 0` | critical |
| ReconciliationStale | 마지막 대사가 15분 넘게 지남 | warning |
| MarketHalted | `fanchart_markets_halted > 0` | critical |
| SlowOrders | 주문 API p99가 5분 동안 250ms 초과 | warning |

## 경보가 울리면

**LedgerOutOfBalance**

1. 로그에서 `reconciliation failed`를 찾는다. 어긋난 계정·자산·칸과 합이 0이 아닌 전표가 함께 찍힌다.
2. 그 계정의 최근 전표(`ledger_journals`)와 주문·체결을 시간순으로 맞춰 본다.
3. 원인을 고치기 전까지 잔고를 손으로 맞추지 않는다. 고칠 때도 잔고를 직접 바꾸지 말고 보정 전표를 남긴다.

**MarketHalted**

1. 로그에서 그 구단의 복구 실패 원인을 찾는다.
2. 원인을 고친 뒤 서버를 다시 띄우면 기동 복구가 호가창을 DB에서 다시 쌓는다.

## 장애 복구 검증

`tools/chaos/run.sh`는 주문을 받는 도중에 서버를 `kill -9`로 죽이고 다시 띄운다. 이를 여러 번 되풀이하면서 회차마다 다음을 확인한다. CI의 `chaos` 작업이 3회차씩 돌린다.

| 확인 | 방법 |
| --- | --- |
| 응답받은 주문은 사라지지 않는다 | 201을 받은 주문 id가 모두 `orders`에 있고, 체결 수량이 응답 때 이상이다 |
| 응답 못 받은 주문은 다시 보내도 한 번만 처리된다 | 같은 `clientOrderId`로 재시도해 5xx가 없고, 같은 번호가 두 주문으로 갈라지지 않는다 |
| 접수만 되고 정산 전에 죽은 주문은 정리된다 | 재시작 뒤 `PENDING` 0건 |
| 돈이 새거나 묶인 채 남지 않는다 | 묶인 포인트 = 대기 매수 예치 + 청약 중 증거금, 원장 대사 일치 |

```bash
cd backend && docker compose up -d && cd ..
ROUNDS=3 USERS=20 tools/chaos/run.sh
```

2026-11-02 로컬 실행에서는 3회차 모두 통과했다. 이때 주문 1,736건을 보냈고, 응답을 못 받은 28건을 재시도했다. 한 회차는 죽는 순간 `PENDING` 주문이 2건 있었고, 재시작할 때 취소·예치 해제됐다.

## 리그 확장: 구단 등록과 첫 상장

운영자 계정으로 로그인한 세션에서 한다. CSRF 토큰(XSRF-TOKEN 쿠키 값)을 `X-XSRF-TOKEN` 헤더로 보낸다.

1. 구단을 등록한다. 등록 직후에는 "상장 전"이라 주문을 받지 않는다.
   ```
   POST /api/admin/teams
   {"id":"kt-rolster","name":"KT 롤스터","shortName":"KT","league":"LCK","startPrice":12000,"color":"#e60012"}
   ```
2. 첫 상장 공모를 등록한다(`POST /api/admin/offerings`). 공모가는 첫 기준가의 가격제한폭 안이어야 한다.
3. 청약이 마감되면 배정되고, 상장 시각까지 동시호가로 지정가 주문을 모은다. 상장 시각에 단일가로 체결한 뒤 연속 매매로 넘어간다.

공모를 취소하면 그 구단은 다시 공모할 때까지 거래하지 않는다. 사용자가 적을 때 구단을 늘리면 호가가 얇아지므로 사용자 수를 보고 늘린다(ADR 0019).

## 경기 데이터 수집

KBO·K리그 일정과 결과는 API-Sports에서 받는다(ADR 0024). 키가 없으면 수집하지 않고, 운영자가 `/api/admin/matches`로 넣는다.

1. https://dashboard.api-sports.io 에서 무료 계정을 만들고 키를 `.env`의 `MATCH_FEED_API_KEY`에 넣은 뒤 다시 띄운다. 같은 키로 API-Baseball과 API-Football을 둘 다 쓴다(각각 하루 100회).
2. 리그 번호와 시즌을 확인한다. 기본값은 KBO 5, K리그1 292다. 다르면 `fanchart.match-feed.leagues`를 고친다. 무료 요금제에서 올해 시즌이 막혀 있으면 응답의 `errors`에 이유가 온다.
   ```
   curl -H "x-apisports-key: $KEY" "https://v1.baseball.api-sports.io/leagues?country=South-Korea"
   curl -H "x-apisports-key: $KEY" "https://v3.football.api-sports.io/leagues?country=South-Korea"
   ```
3. 운영자 세션으로 지금 바로 받아 본다. 연결하지 않은 팀은 `unmappedTeams`에 "팀 번호 이름"으로 나온다.
   ```
   POST /api/admin/match-feed/sync
   ```
4. 구단마다 제공처 팀 번호를 잇고 3을 다시 한다. 한 팀 번호는 한 구단에만 이을 수 있다.
   ```
   PUT /api/admin/teams/lg/feed
   {"source":"api-baseball","externalId":"<팀 번호>"}
   ```

그 뒤에는 6시간마다 시즌 일정을, 15분마다 끝났을 경기의 결과를 받는다. 로그에서 `match feed`로 찾는다. 확인할 것:

- `conflicts`: 끝난 경기의 취소, 취소한 경기의 결과, 팀이 바뀐 경기 번호. 자동으로 바꾸지 않았으니 사실을 확인하고 운영자 API로 고친다.
- `moved later ... keeping`: 경기 중에 시작 시각을 늦춘 변경을 받지 않았다. 실제로 늦게 시작했는지 확인한다.
- 시작하고 48시간이 지나도 결과가 없는 경기는 더 묻지 않는다. `GET /api/matches`에서 SCHEDULED로 남은 지난 경기를 보고 결과를 넣거나 취소한다.
- `daily request limit reached`: 그날 요청을 다 썼다. 한국 시간 자정에 다시 센다.
