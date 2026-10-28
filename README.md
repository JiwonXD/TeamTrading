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

로그인 대신 `X-Account-Id` 헤더로 계정을 구분한다. 이상거래 탐지 기록은 아직 메모리에만 있다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/teams` | 구단 시세 목록 |
| GET | `/api/teams/{teamId}` | 구단 시세, 상·하한가 |
| GET | `/api/teams/{teamId}/orderbook?depth=10` | 호가창 |
| GET | `/api/teams/{teamId}/trades?limit=30` | 최근 체결 (익명) |
| POST | `/api/accounts` | 계정 만들기 (100만P 지급) |
| GET | `/api/accounts/me` | 내 포인트·보유 구단·수익률 |
| GET | `/api/accounts/me/orders` | 내 대기 주문 |
| POST | `/api/orders` | 주문. `clientOrderId`가 같은 재시도는 처음 결과를 그대로 돌려주고, 같은 번호로 다른 주문을 보내면 409 |
| DELETE | `/api/orders/{orderId}` | 주문 취소 |
| GET | `/api/rankings` | 수익률 랭킹 |
| GET | `/api/surveillance/alerts` | 이상거래 탐지 기록 (운영자용) |
| GET | `/api/surveillance/reconciliation` | 원장과 잔고 대사 결과 (운영자용) |

## 프론트엔드

```bash
cd frontend
npm install
npm run dev     # 개발 서버
npm test        # 매칭 엔진 테스트
npm run build   # dist/index.html 한 파일로 빌드
```
