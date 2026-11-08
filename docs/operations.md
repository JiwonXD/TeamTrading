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
2. 둘 중 하나로 주식을 만든다.
   - **운영자 매도(증자, 기본, ADR 0031):** 운영자 계정으로 지정가 매도를 낸다(`POST /api/orders`, 주문 번호 `issue-<구단 id>`). 운영자 계정은 주식이 없어도 팔 수 있고, 모자란 만큼 새로 발행된다. 이 매도가 접수되면 그 구단 거래가 열린다. 가격은 기준가의 가격제한폭(±30%) 안이어야 한다.
   - **첫 상장 공모:** `POST /api/admin/offerings`. 공모가는 첫 기준가의 가격제한폭 안이어야 한다. 청약이 마감되면 배정되고, 상장 시각까지 동시호가로 지정가 주문을 모은다. 상장 시각에 단일가로 체결한 뒤 연속 매매로 넘어간다.

전 구단을 한 번에 등록·ESPN 번호 연결·운영자 매도(10,000P × 1,000주, 다 팔린 구단은 시세 근처에서 운영자가 더 건다)하려면 `deploy/ops/2026-11-07-all-teams.js`를 운영자 계정으로 로그인한 탭의 개발자 도구 콘솔에 붙여 넣는다(여러 번 실행해도 된다). 기존 구단의 임시 기준가는 먼저 `deploy/ops/2026-11-07-start-price-10000.sql`로 10,000P에 맞추고 백엔드를 다시 띄운다(체결이 있는 구단은 바꾸지 않는다).

ESPN 팀 번호는 종목마다 따로 매겨 겹칠 수 있어, 축구가 아닌 종목은 종목을 붙여 연결한다(NBA 레이커스 = `basketball:13`).

공모를 취소하면 그 구단은 다시 공모할 때까지 거래하지 않는다. 사용자가 적을 때 구단을 늘리면 호가가 얇아지므로 사용자 수를 보고 늘린다(ADR 0019).

## 경기 데이터 수집

키는 제공처마다 하나이고, 같은 키로 여러 대회(리그 정규 시즌·컵대회)를 부른다(ADR 0024, 0027, 0028). ESPN은 키 없이 받는다(ADR 0029). 키를 비운 제공처의 대회와 설정에서 `enabled: false`로 끈 대회만 수집하지 않고, 그 대회 경기는 운영자가 `/api/admin/matches`로 넣는다.

| 키 변수 | 제공처 | 대회(설정의 대회 번호) | 무료 한도 |
| --- | --- | --- | --- |
| `API_SPORTS_KEY` | API-Sports(`api-baseball`, `api-football`) | KBO(5), K리그(292). 둘 다 지금은 닫아 둔 리그(HIDDEN_LEAGUES, ADR 0033)라 수집하지 않는다 | API마다 하루 100회. 무료는 2022~2024 시즌만 열려 올해 일정은 유료(Pro, API마다 월 19달러)가 필요하다 |
| `FOOTBALL_DATA_KEY` | football-data.org(`football-data`) | 프리미어리그(PL), 라리가(PD), 세리에A(SA), 분데스리가(BL1), 챔피언스리그(CL) | 분당 10회 |
| `PANDASCORE_KEY` | PandaScore(`pandascore`) | LCK(293), MSI(300), 롤드컵(297). LCK컵·EWC는 번호 확인 뒤 더한다 | 시간당 1,000회 |
| (키 없음) | ESPN(`espn`, 공개 문서 없음) | NBA(`basketball/nba`), 유로파(`soccer/uefa.europa`), 컨퍼런스(`soccer/uefa.europa.conf`), FA컵(`soccer/eng.fa`), EFL컵(`soccer/eng.league_cup`), 코파 델 레이(`soccer/esp.copa_del_rey`), 코파 이탈리아(`soccer/ita.coppa_italia`), DFB 포칼(`soccer/ger.dfb_pokal`), ACL(`soccer/afc.champions`) | 공식 한도 없음. 하루씩 묻고 우리 쪽 상한 하루 1,500회. 승부차기로 끝난 경기는 운영자가 결과를 넣는다 |

1. 키를 받아 `.env`에 넣고 다시 띄운다.
   - API-Sports: https://dashboard.api-sports.io 가입 → 대시보드의 API Key
   - football-data.org: https://www.football-data.org/client/register 가입 → 메일로 온 API token
   - PandaScore: https://app.pandascore.co 가입 → 대시보드의 access token
2. 운영자 세션으로 대회별 수집 여부를 본다. `active`가 false면 제공처 키가 비었거나 꺼 둔 대회다.
   ```
   GET /api/admin/match-feed
   ```
3. 대회 번호와 올해 시즌이 무료 요금제로 열리는지 확인한다. 위 표의 번호는 아직 확인하지 않은 값이다. 다르면 `fanchart.match-feed.competitions`를 고친다. 막혀 있으면 API-Sports는 응답의 `errors`에, football-data는 HTTP 400·403과 `message`에, PandaScore는 HTTP 401·403과 `error`에 이유가 온다. 수집 결과의 `error`에도 그 이유가 같이 실린다.
   ```
   curl -H "x-apisports-key: $KEY" "https://v1.baseball.api-sports.io/leagues?country=South-Korea"
   curl -H "x-apisports-key: $KEY" "https://v3.football.api-sports.io/leagues?id=45&current=true"
   curl -H "X-Auth-Token: $KEY" "https://api.football-data.org/v4/competitions/CL"
   curl -H "Authorization: Bearer $KEY" "https://api.pandascore.co/lol/leagues?search%5Bname%5D=LCK"
   curl -H "Authorization: Bearer $KEY" "https://api.pandascore.co/lol/leagues?search%5Bname%5D=MSI"
   curl "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard?dates=20261106"   # ESPN 팀 번호는 competitors[].team.id
   ```
4. 구단마다 **대회에 쓰는 제공처 번호를 모두** 잇는다. 한 구단은 제공처마다 번호 하나를 가진다(예: 리버풀은 리그·챔스용 `football-data`와 FA컵용 `api-football`). 컵대회는 잇지 않은 우리 구단을 상장하지 않은 팀으로 들이므로, 컵대회 일정을 받기 전에 먼저 잇는다. 한 제공처의 한 팀 번호는 한 구단에만 이을 수 있다.
   ```
   PUT /api/admin/teams/lg/feed
   {"source":"api-baseball","externalId":"<팀 번호>"}
   GET /api/admin/teams/lg/feed      # 이 구단의 제공처별 번호
   ```
5. 지금 바로 받아 본다. 대회별 `unmappedTeams`에 잇지 않은 팀이 "팀 번호 이름"으로 나온다. 리그는 그 경기를 건너뛰었고, 컵대회는 우리 구단과 붙은 상대만 나온다(우리 구단인데 잇지 않은 것인지 본다). 아직 등록하지 않은 구단은 먼저 등록하고 첫 상장 공모를 연다(위 "리그 확장: 구단 등록과 첫 상장").
   ```
   POST /api/admin/match-feed/sync
   ```
6. 컵대회 최종 성적 수당은 대회를 정해 등록한다. 비우면 구단 소속 리그의 정규 시즌이다. 금액은 W × 성적 배수 × 대회 배수다.
   ```
   POST /api/admin/awards
   {"competition":"UCL","season":"2026","teamId":"<구단>","award":"QUARTERFINAL","recordAt":"...","sourceUrl":"https://..."}
   ```

그 뒤에는 6시간마다 시즌 일정을, 15분마다 끝났을 경기의 결과를 받는다. 로그에서 `match feed`로 찾는다. 확인할 것:

- `conflicts`: 끝난 경기의 취소, 취소한 경기의 결과, 팀이 바뀐 경기 번호. 자동으로 바꾸지 않았으니 사실을 확인하고 운영자 API로 고친다.
- `moved later ... keeping`: 경기 중에 시작 시각을 늦춘 변경을 받지 않았다. 실제로 늦게 시작했는지 확인한다.
- 시작하고 48시간이 지나도 결과가 없는 경기는 더 묻지 않는다. `GET /api/matches`에서 SCHEDULED로 남은 지난 경기를 보고 결과를 넣거나 취소한다.
- `daily request limit reached`: 그 제공처·키로 그날 요청을 다 썼다. 같은 키를 쓰는 대회끼리 함께 센다. 한국 시간 자정에 다시 센다. API-Sports 축구는 컵대회가 많아 빠듯하므로 필요 없는 대회는 끈다.

## 구단 뉴스

구단 화면의 뉴스는 네이버 검색 API(뉴스)로 받는다(ADR 0035). 키가 없으면 뉴스 탭이 "준비 중"으로 보이고 나머지는 그대로 돈다.

1. https://developers.naver.com 에 네이버 아이디로 로그인 → Application → 애플리케이션 등록.
2. 애플리케이션 이름(예: 팬차트), 사용 API는 **검색**, 비로그인 오픈 API 서비스 환경은 **WEB 설정**에 `https://fanchart.kr`을 넣고 등록한다. 검수 없이 바로 쓸 수 있다.
3. 내 애플리케이션 화면의 Client ID와 Client Secret을 `deploy/.env`에 넣고 다시 띄운다.
   ```
   NAVER_CLIENT_ID=...
   NAVER_CLIENT_SECRET=...
   ```
4. `GET /api/teams/t1/news`가 `"available":true`와 기사를 주면 된다.

하루 25,000회까지 무료다. 서버는 구단마다 15분 동안 결과를 모아 두고 같이 쓰므로 140개 구단을 계속 봐도 하루 13,440회를 넘지 않는다. 받다가 실패하면 로그에 `news for ... failed`가 남고, 앞서 받아 둔 기사가 있으면 그것을 보여준다.
