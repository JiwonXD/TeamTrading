# TeamTrading (팀트)

스포츠 구단을 무료 포인트로 사고파는 모의투자 서비스. 유저끼리 호가창에서 매매하는 HTS 경험을 목표로 한다.
포인트는 무료이고 환전·전송이 불가하다.

## 구성

| 폴더 | 내용 | 스택 |
| --- | --- | --- |
| `frontend/` | 모바일 우선 화면. 현재는 서버 없이 브라우저 안에서 시뮬레이션 계정과 매매 | React, TypeScript, Vite |
| `backend/` | 주문 접수·매칭·정산 서버와 REST API | Java 21, Spring Boot 4 |

## 매매 규칙

- 가격-시간 우선 연속 매매, 지정가·시장가 주문 (시장가 잔량은 즉시 취소)
- 가격제한폭: 전일 종가 기준 ±30%, KRX식 호가가격단위
- 자기매매 방지: 내 반대 주문과 체결될 상황이면 새 주문 잔량 취소
- 익명 호가: 호가창·체결 내역에 상대 계정을 노출하지 않음
- 이상거래 탐지: 직전 체결가 대비 10% 이상 괴리 체결, 10분 내 같은 상대와 5회 이상 체결

## 문서

- [매매 규칙서](docs/rulebook.md): 규칙별 KRX 기준, 출처, 팀트 적용값과 다른 점
- [결정 기록](docs/adr/README.md): 저장소, 동시성, 트랜잭션, 멱등성, 원장, 실시간 시세 설계 결정과 대안

## 백엔드

```bash
cd backend
./gradlew test           # 엔진·서비스·API 테스트 (H2 MySQL 모드)
docker compose up -d     # 로컬 MySQL 8.4
./gradlew bootRun        # http://localhost:8080, 기동 시 Flyway가 스키마를 만든다
```

### 주문 처리

매칭은 메모리 호가창에서, 잔고와 주문 상태는 MySQL에서 관리한다. 주문 하나는 트랜잭션 두 개로 처리한다.

1. 접수: 포인트·주식을 조건부 UPDATE(`WHERE available >= ?`)로 묶고 주문을 `PENDING`으로 저장한다. 영향 행이 0이면 거절한다.
2. 매칭: 종목 락 안에서 메모리 호가창과 매칭한다. 돈이 이미 묶여 있어 매칭 중에 잔고를 다시 보지 않는다.
3. 정산: 체결, 상대 주문 잔량, 차액 환불, 주문 상태를 한 트랜잭션으로 반영한다.

- 동시성: 같은 종목 주문은 종목 락으로 순서대로, 다른 종목은 동시에 처리한다. 여러 종목의 정산이 같은 계정을 건드릴 수 있어 계정 행은 id 순서로 갱신해 InnoDB 교착을 피한다.
- 정합성: 잔고·수량 칼럼에 `CHECK (>= 0)`를 두고, 동시성 테스트로 포인트·주식 총량과 "묶인 포인트 = 대기 매수 주문 예치 합"을 검사한다.
- 멱등성: `UNIQUE (account_id, client_order_id)`. 같은 번호로 다시 보내면 저장된 결과를 그대로 돌려준다.
- 원장: 포인트·주식이 움직일 때마다(계정 개설, 초기 지급, 주문 예치, 체결, 예치 해제) 같은 트랜잭션에서 복식부기 전표를 남긴다. 전표는 자산별 합이 0이어야 하고, 처음 생겨나는 몫은 발행처(`SYSTEM`) 칸에서 나온다.
- 대사: 원장을 계정·자산·칸(가능/묶임)별로 합산해 `accounts`·`positions` 잔고와 비교하고, 합이 0이 아닌 전표를 찾는다. 두 쪽을 한 읽기 트랜잭션에서 읽어 같은 시점끼리 비교한다.
- 복구: 정산이 실패하면 그 주문을 취소하고 호가창을 DB에서 다시 쌓는다. 기동 시에는 `PENDING`으로 남은 주문을 취소하고 `OPEN` 주문을 접수 순서대로 읽어 호가창을 복구한다. 복구마저 실패하면 그 종목 거래를 멈춘다.

- 포인트 발행: 가입·광고·게시글 등 포인트가 새로 생기는 경로는 모두 `point_grants`에 `(source, source_ref)` 멱등키로 기록하고 경로별 하루 한도를 둔다. 대사는 원장 발행 총량과 발행 기록 합도 비교한다.
- 로그인: 카카오·네이버·구글 OAuth 로그인 후 세션 쿠키로 계정을 구분한다. 상태를 바꾸는 요청에는 CSRF 토큰(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더)이 필요하다. 소셜 로그인 키는 `oauth` 프로필(`application-oauth.yml`)의 환경 변수로 넣고, `demo` 프로필은 키 없이 닉네임으로 들어가는 개발용 로그인을 연다. 운영자 계정은 `teamtrading.auth.admins`에 계정 id로 지정한다.

- 공모: 구단 주식은 공모 청약으로 처음 푼다. 청약금은 청약할 때 묶고, 마감 뒤 공모 수량 절반은 균등배정, 나머지는 비례배정한다. 배정분 대금은 소각하고 나머지는 돌려준다. 그 구단 주식이 하나도 없을 때 연 공모(첫 상장)는 배정 전에는 주문을 받지 않고, 상장 시각까지 동시호가로 지정가 주문을 모았다가 체결 수량이 가장 많은 한 가격(시초가)에 체결한다. 대사는 원장의 소각 합과 배정 기록도 비교한다.
- 종목토론방: 글은 서버에 저장한다. 게시글 보상(2,000P, 하루 3번)은 글을 쓰고 10분이 지나도 숨겨지지 않았을 때 준다. 신고 3건이 쌓이면 자동으로 숨기고, 운영자가 숨기거나 되돌릴 수 있다.
- 리더보드: KST 자정마다 구단 종가(마감 전 10분 VWAP)와 계정별 평가금·외부 유입을 기록하고, 일 단위 시간가중수익률로 순위를 매긴다. 활동 보상과 지급받은 주식은 수익에서 빼고 배당은 수익에 넣는다. 서버가 멈춰 있던 날은 기동할 때 원장에서 되짚어 채운다.

- 관측성: 관리 포트(8081)에서 health와 Prometheus 지표를 연다. 5분마다 대사를 돌려 결과를 지표로 남기고, 체결·거래 정지·실시간 시세 세션도 지표로 낸다. 경보 규칙과 대응 절차는 [docs/operations.md](docs/operations.md).

이상거래 탐지 기록은 아직 메모리에만 있다. 그래서 재시작하면 리더보드의 이상거래 제외 대상도 비워진다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/teams` | 구단 시세 목록 |
| GET | `/api/teams/{teamId}` | 구단 시세, 상·하한가 |
| GET | `/api/teams/{teamId}/orderbook?depth=10` | 호가창. 실시간 시세와 같은 `epoch`·`seq`를 함께 준다 |
| GET | `/api/teams/{teamId}/trades?limit=30` | 최근 체결 (익명) |
| GET | `/api/auth/options` | 로그인 방법 (소셜 로그인 제공자, 개발용 로그인 여부) |
| GET | `/oauth2/authorization/{kakao\|naver\|google}` | 소셜 로그인 시작. 처음 로그인하면 계정을 만들고 100만P 지급 |
| POST | `/api/auth/dev-login` | 개발용 닉네임 로그인 (`demo` 프로필에서만) |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/accounts/me` | 내 포인트·보유 구단·수익률 |
| GET | `/api/accounts/me/orders` | 내 대기 주문 |
| GET | `/api/accounts/me/trades?limit=20` | 내 체결 내역 |
| POST | `/api/orders` | 주문. `clientOrderId`가 같은 재시도는 처음 결과를 그대로 돌려주고, 같은 번호로 다른 주문을 보내면 409 |
| DELETE | `/api/orders/{orderId}` | 주문 취소 |
| GET | `/api/rankings` | 수익률 랭킹. 로그인했으면 내 행에 `me: true` (계정 id는 내보내지 않음) |
| GET | `/api/leaderboard?period=TODAY&sort=RETURN&limit=50` | 기간별(`TODAY`·`WEEK`·`MONTH`·`SEASON`) 시간가중수익률·손익 순위와 내 순위, 순위 밖이면 그 이유 |
| GET | `/api/offerings?limit=10` | 공모 목록. 청약자 수와 청약 수량 합(경쟁률)을 함께 준다 |
| GET | `/api/teams/{teamId}/offerings` | 구단별 공모 |
| POST | `/api/offerings/{offeringId}/subscriptions` | 청약 `{quantity}`. 청약금을 바로 묶는다. 같은 수량 재시도는 처음 청약을 돌려준다 |
| DELETE | `/api/offerings/{offeringId}/subscriptions/me` | 청약 취소 (청약 기간 안에만) |
| GET | `/api/accounts/me/subscriptions` | 내 청약과 배정 결과 |
| POST | `/api/admin/offerings` | 공모 등록 (운영자만). `/allocate`로 바로 배정, `/cancel`로 취소 |
| GET | `/api/teams/{teamId}/posts?before=&limit=30` | 종목토론방 글 (최신순, `before`로 더 보기). 내 글이면 `mine`과 보상 상태 |
| POST | `/api/teams/{teamId}/posts` | 글쓰기 `{body}`. 5~300자, 링크 금지, 20초 간격, 하루 30개 |
| DELETE | `/api/posts/{postId}` | 내 글 지우기 |
| POST | `/api/posts/{postId}/reports` | 신고 `{reason: SPAM\|ABUSE\|AD\|OTHER}`. 다른 계정 3명이 신고하면 숨김 |
| GET | `/api/surveillance/alerts` | 이상거래 탐지 기록 (닉네임만 공개) |
| GET | `/api/surveillance/reconciliation` | 원장·잔고·발행 기록 대사 결과 (운영자만) |

### 실시간 시세

`ws://localhost:8080/ws/teams/{teamId}`에 접속하면 `snapshot` 메시지를 한 번 받고, 그 뒤로 호가창이 바뀔 때마다 `update`를 받는다. 두 메시지 모두 시세(`quote`), 위아래 10단계 호가(`asks`, `bids`), 체결(`trades`, 최신이 앞)을 담는다. `snapshot`의 체결은 최근 30건, `update`의 체결은 이번에 새로 난 것만이다.

- `seq`는 구단마다 1씩 늘어난다. 받은 번호가 건너뛰면 다시 접속해 새 스냅숏부터 받는다. `epoch`은 서버가 뜰 때마다 바뀐다.
- 구독 등록과 스냅숏은 종목 락 안에서 함께 처리해서, 스냅숏과 첫 업데이트 사이에 빠지거나 앞서는 업데이트가 없다.
- 업데이트는 매칭 스레드에서 세션별 큐에 넣기만 하고 전송은 세션마다 둔 가상 스레드가 한다. 큐(256건)가 넘친 느린 클라이언트는 연결을 끊는다.

## 프론트엔드

```bash
cd frontend
npm install
npm run dev          # 브라우저 안 시뮬레이션 모드 (서버 없이)
npm run dev:server   # 서버 모드: /api, /ws를 localhost:8080 백엔드로 넘긴다
npm test             # 매칭 엔진, 실시간 시세 처리 테스트
npm run build        # dist/index.html 한 파일로 빌드 (시뮬레이션 모드, 미리보기용)
```

서버 모드로 전체를 띄우는 순서:

```bash
cd backend && docker compose up -d
SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun   # demo: 시뮬레이션 계정 20개가 주문 API로 함께 거래
cd frontend && npm run dev:server
npm run e2e                                     # Playwright E2E + axe 접근성 검사 (위 두 개가 떠 있어야 함)
```

- 서버 모드 화면은 시세 목록·내 계좌·랭킹·감시 기록을 3초마다 받아오고, 보고 있는 구단의 호가와 체결은 WebSocket으로 받는다. 받은 `seq`가 건너뛰면 다시 접속해 스냅숏부터 받는다.
- 주문은 `clientOrderId`를 먼저 정해 보내고, 응답을 못 받으면 같은 번호로 한 번 더 보낸다.
