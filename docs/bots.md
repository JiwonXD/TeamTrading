# 자동매매 봇

봇 하나가 화면을 켜 둔 사용자 한 명처럼 움직인다. 두 가지로 쓴다.

- **실서버:** 사이트가 비어 보이지 않게 봇 몇십 개가 사고판다.
- **부하 측정:** 운영과 분리한 스택에 봇 1,000개를 붙여 서버가 어디서 막히는지 잰다. 결과는 [performance.md](performance.md) "봇 1,000개 화면 부하".

결정 배경은 [ADR 0040](adr/0040-trading-bots.md).

## 봇 계정

- 봇 실행기는 `POST /api/auth/bot-login`에 `X-Bot-Token` 헤더(서버 `.env`의 `BOT_TOKEN`)와 이름(`bot-0001` 등)을 보내 로그인한다. 처음 보는 이름이면 일반 가입처럼 계정을 만들고 가입 포인트(1,000,000P)를 준다. `BOT_TOKEN`이 비어 있으면 이 경로는 404다.
- 비밀 값으로 막으므로 가입·로그인 IP 제한(트러블슈팅 46)을 받지 않는다. 봇 1,000개를 한 기기에서 띄운다.
- 봇은 일반 계정이다. 운영자와 달리 가진 포인트·주식 안에서만 사고팔고, 새 주식이나 포인트를 만들지 않는다. 배당은 받는다.
- 봇은 `/api/rankings`와 리더보드에서 빠진다. 봇끼리의 체결은 이상거래 탐지에서 빠지고, 봇과 사람의 체결은 그대로 검사한다.

## 봇이 하는 일

`tools/bots/bots.mjs`(Node 22, 의존성 없음). 화면(`store.server.ts`)과 같은 요청을 보낸다.

- 3초마다 화면 새로 고침 요청 하나(`/api/refresh`, 보고 있는 구단 토론 포함). 2026-11-12 전에는 요청 11개였다([ADR 0041](adr/0041-one-request-per-refresh.md)).
- 보고 있는 구단의 실시간 시세 WebSocket 하나. 평균 90초마다 다른 구단으로 옮긴다. 열 번 중 일곱 번은 앞쪽 20개 구단을 본다.
- 평균 30초마다 지정가 주문 하나: 현재가 ±3호가, 1~3주. 주식이 있으면 반반으로 사거나 팔고, 대기 주문이 5개를 넘으면 오래된 것부터 취소한다. 닫아 둔 리그와 상장 전 구단은 건너뛴다.
- 세션이 사라지면(서버 재배포) 다시 로그인한다. 점검시간(00:00~00:30) 주문 거절은 4xx로 세고 넘어간다.

설정(환경 변수): `BASE_URL`, `BOT_TOKEN`, `BOTS`(봇 수), `RAMP_SECONDS`(다 띄우는 데 걸리는 시간), `ORDER_EVERY`, `SWITCH_EVERY`, `POLL_MS`, `DURATION`(초, 0이면 계속), `REPORT`(출력 간격).

## 출력 읽기

```
[02:04:52] 봇 300/300 WS 300 | 조회 99.8/s p50 7ms p95 15ms p99 26ms 4xx 0 5xx 0 연결오류 0 | 주문 9.8/s p50 7ms p95 16ms ... | 시세 수신 72/s | 로그인 실패 0
```

- 봇 로그인 수 / 목표, 열린 WebSocket 수
- 조회: 새로 고침의 초당 횟수와 새로 고침 한 번에 걸린 시간. 서버가 버거우면 p95가 먼저 뛰고, 봇이 로그인하지 못해 "봇 N/목표"가 덜 찬다.
- 주문: 주문·취소의 초당 건수와 지연
- 로그인 실패: 그 사이 로그인하지 못한 횟수. 실패한 봇은 5~10초 뒤 다시 로그인한다. 서버가 연결을 받지 못하면 이 수와 연결오류가 함께 는다
- 끝날 때(`DURATION`, Ctrl+C, `docker compose stop`) `[전체]` 줄로 전체 요약을 찍는다.

## 실서버에 봇 띄우기

`deploy/.env`에 두 줄을 넣는다. `BOT_TOKEN`은 아무도 모르는 긴 임의 문자열이다(PowerShell에서 `[guid]::NewGuid()`를 두 번 붙여 쓰면 된다).

```
BOT_TOKEN=...
BOTS=20
```

```powershell
cd D:\dev\TeamTrading\deploy; git pull origin main; docker compose --profile tunnel --profile bots up -d --build
docker compose logs -f bots
```

봇은 nginx(`http://web:80`)로 붙어 바깥 사용자와 같은 길을 지난다. 봇 수는 아래 부하 측정에서 지연이 버티는 수 안으로 정한다. 끄려면 `docker compose stop bots`.

## 운영과 분리해 부하 재기

`deploy/compose.load.yml`이 운영 스택을 다른 이름(`fanchart-load`)·다른 DB·다른 포트(8089)로 한 벌 더 띄운다. 측정 DB에는 `deploy/backups`의 가장 최근 운영 백업을 넣으므로 구단·호가·운영자 매도가 운영과 같다. 바깥 API(경기 데이터·뉴스·메일)는 부르지 않고, 운영자 할인도 끈다. 운영 장부와 사용자 화면은 건드리지 않는다.

```powershell
cd D:\dev\TeamTrading\deploy; git pull origin main; docker compose -f docker-compose.yml -f compose.load.yml up -d --build web bots
docker compose -f docker-compose.yml -f compose.load.yml logs -f bots
```

- 봇 1,000개를 5분(`LOAD_RAMP_SECONDS`)에 걸쳐 늘리고 15분(`LOAD_SECONDS`) 뒤 멈춘다. 30초마다 찍히는 줄에서 p95가 뛰기 시작하는 봇 수가 이 PC의 한계다.
- 다시 재려면 같은 명령을 다시 실행한다. 측정 DB는 그대로 두고 봇만 새로 돈다. 백업부터 다시 넣으려면 아래 정리 명령 뒤에 실행한다.
- 다 쟀으면 측정 스택과 측정 DB를 지운다(운영 스택·운영 DB는 이름이 달라 그대로다).

```powershell
cd D:\dev\TeamTrading\deploy; docker compose -f docker-compose.yml -f compose.load.yml down -v
```

주의:

- 측정 스택과 봇이 운영 서버와 같은 PC의 CPU를 나눠 쓴다. 측정 중에는 실서버도 느려질 수 있으니 사람이 적은 시간에 잰다.
- 점검시간(00:00~00:30)에는 주문이 거절된다.
