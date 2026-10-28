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
./gradlew test      # 엔진·서비스·API 테스트
./gradlew bootRun   # http://localhost:8080
```

종목마다 락을 하나 두어 같은 종목 주문은 순서대로, 다른 종목은 동시에 처리한다.
계정 잔고 변경은 계정 단위로 원자적이고 두 계정 락을 함께 잡지 않아 교착 상태가 없다.
동시성 테스트로 여러 스레드가 주문·취소해도 포인트와 주식 총량이 보존되는지 확인한다.
현재 저장소는 메모리이며, 로그인 대신 `X-Account-Id` 헤더로 계정을 구분한다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/teams` | 구단 시세 목록 |
| GET | `/api/teams/{teamId}` | 구단 시세, 상·하한가 |
| GET | `/api/teams/{teamId}/orderbook?depth=10` | 호가창 |
| GET | `/api/teams/{teamId}/trades?limit=30` | 최근 체결 (익명) |
| POST | `/api/accounts` | 계정 만들기 (100만P 지급) |
| GET | `/api/accounts/me` | 내 포인트·보유 구단·수익률 |
| GET | `/api/accounts/me/orders` | 내 대기 주문 |
| POST | `/api/orders` | 주문 |
| DELETE | `/api/orders/{orderId}` | 주문 취소 |
| GET | `/api/rankings` | 수익률 랭킹 |
| GET | `/api/surveillance/alerts` | 이상거래 탐지 기록 (운영자용) |

## 프론트엔드

```bash
cd frontend
npm install
npm run dev     # 개발 서버
npm test        # 매칭 엔진 테스트
npm run build   # dist/index.html 한 파일로 빌드
```
