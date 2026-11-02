# 배포

`deploy/`에 서버 한 대에 올리는 묶음이 있다.

| 구성 | 역할 |
| --- | --- |
| `mysql` | MySQL 8.4. 데이터는 `mysql-data` 볼륨 |
| `backend` | 서버. `backend/Dockerfile`로 만든 이미지. 비루트 사용자로 실행. 8080(서비스)과 8081(관리)은 컨테이너 네트워크 안에서만 열린다 |
| `web` | nginx. 화면(`frontend/dist`)을 주고 `/api`, `/ws`, 소셜 로그인 경로를 서버로 넘긴다. 바깥에 여는 포트는 이것 하나 |
| `prometheus` | 서버 지표 수집과 경보 규칙(`deploy/prometheus/alerts.yml`). 서버 안(127.0.0.1:9090)에서만 열린다 |

## 올리기

필요: Docker(Compose v2), Java 21, Node 22.

```bash
cd deploy
cp .env.example .env      # DB 비밀번호, 소셜 로그인 키를 채운다
./build.sh                # 서버 jar, 화면(서버 모드) 빌드, 서버 이미지 생성
docker compose up -d
docker compose ps         # backend가 healthy가 되면 web이 뜬다
```

- 키 없이 먼저 시험하려면 `.env`에서 `SPRING_PROFILES_ACTIVE=demo`로 둔다. 닉네임 로그인과 시연용 가상 매매·경기가 켜진다. 운영에서는 `oauth`로 둔다.
- 운영자 지정: 운영할 계정으로 로그인한 뒤 `/api/accounts/me`의 `id`를 `ADMIN_ACCOUNT_IDS`에 넣고 `docker compose up -d backend`로 다시 띄운다.
- 실제 경기 일정·결과(KBO·K리그)를 받으려면 API-Sports 무료 키를 `MATCH_FEED_API_KEY`에 넣고 구단과 제공처 팀 번호를 잇는다([운영 점검](operations.md#경기-데이터-수집)). 비워 두면 운영자가 경기를 직접 넣는다.
- 소셜 로그인 콜백 주소는 `https://{도메인}/login/oauth2/code/{kakao|naver|google}`이다. 서버는 nginx가 넘기는 `X-Forwarded-*`를 믿는다(`SERVER_FORWARD_HEADERS_STRATEGY=native`). 그래서 콜백 주소와 쿠키의 Secure 속성이 바깥 주소 기준으로 정해진다.

## 베타: 집 PC에서 돌리기

사용자가 적은 동안은 유료 서버 없이 집 PC에서 같은 묶음을 돌린다. 바깥 연결은 Cloudflare Tunnel(무료)로 한다. PC가 Cloudflare 쪽으로 연결을 거는 방식이다. 그래서 공유기 포트를 열지 않고, 집 IP도 드러나지 않으며, HTTPS 인증서도 Cloudflare가 붙인다.

1. Docker Desktop을 설치하고 "로그인 시 시작"을 켠다. Windows에서는 WSL2(Ubuntu)에서 저장소를 받고 아래 명령을 실행한다.
2. 도메인(.kr 등)을 Cloudflare에 연결한다(네임서버 변경).
3. Cloudflare Zero Trust → Networks → Tunnels에서 터널을 만든다.
   - 커넥터 종류는 Docker를 고르고, 화면에 나오는 토큰을 `.env`의 `CLOUDFLARE_TUNNEL_TOKEN`에 넣는다.
   - Public Hostname에서 도메인을 서비스 `http://web:80`으로 연결한다.
4. 묶음과 터널을 함께 띄운다.
   ```bash
   cd deploy
   ./build.sh
   docker compose --profile tunnel up -d
   ```
5. 소셜 로그인 콜백 주소를 `https://{도메인}/login/oauth2/code/{kakao|naver|google}`로 등록한다. 키가 준비되기 전에는 `SPRING_PROFILES_ACTIVE=demo`로 닉네임 로그인만 열어 지인 테스트를 한다.

감수할 점:

- PC가 꺼지거나 잠자기에 들어가면 서비스도 멈춘다. 전원 설정에서 잠자기를 끈다. 컨테이너는 `restart: unless-stopped`라 Docker가 뜨면 다시 올라온다.
- 백업은 직접 챙긴다. 아래 "백업"의 덤프 명령을 매일 돌린다(WSL의 cron이나 Windows 작업 스케줄러). 덤프 파일은 PC 밖(클라우드 드라이브 등)에도 둔다.
- 처리량의 상한은 집 업로드 속도와 PC 사양이다. 사용자가 늘면 같은 묶음을 클라우드 VM에 그대로 옮기고, Cloudflare의 Public Hostname만 새 서버로 바꾼다.

## HTTPS

nginx는 80번만 연다. TLS는 앞단에서 끝낸다.

- AWS: ALB에 ACM 인증서를 붙이고 대상 그룹을 이 서버의 80번으로 둔다. ALB가 `X-Forwarded-Proto: https`를 넘긴다.
- 서버 한 대: 앞에 Caddy를 두면 인증서 발급과 갱신을 자동으로 한다.

## 업데이트

```bash
git pull
deploy/build.sh
docker compose -f deploy/docker-compose.yml up -d
```

서버는 종료 신호를 받으면 처리 중인 요청을 끝내고 내려간다(최대 30초). 기동할 때 DB에서 호가창을 다시 쌓는다. 그동안 들어온 주문은 nginx가 502로 돌려준다. 클라이언트는 같은 `clientOrderId`로 다시 보내면 된다. 강제 종료 중에도 장부가 맞는지는 `tools/chaos/run.sh`로 확인한다(운영 점검 문서 참고).

DB 스키마는 Flyway가 기동할 때 올린다. 이미 적용된 마이그레이션 파일은 고치지 않는다. 고치면 체크섬이 달라져 서버가 뜨지 않는다.

## 백업

- 같은 서버의 MySQL이면 매일 `mysqldump --single-transaction`을 떠서 다른 곳(S3 등)에 둔다.
  ```bash
  docker compose -f deploy/docker-compose.yml exec -T mysql sh -c 'mysqldump --single-transaction -uroot -p"$MYSQL_ROOT_PASSWORD" teamtrading' | gzip > teamtrading-$(date +%F).sql.gz
  ```
- 관리형 DB(RDS 등)를 쓰면 자동 백업과 시점 복구를 켠다. 이때는 `mysql` 서비스를 빼고 `backend`의 `SPRING_DATASOURCE_URL`을 그 DB 주소로 바꾼다.

## 확인한 것 (2026-11-02, 로컬 Docker)

- 네 컨테이너가 모두 healthy로 떴다.
- nginx를 거쳐 다음이 됐다: 화면, 시세 API, 로그인(CSRF 포함), 주문, WebSocket 실시간 시세 스냅숏.
- Prometheus가 서버 지표를 수집했고(`up`), 경보 규칙 4개가 로드됐다.
- 서버 컨테이너를 재시작해도 대기 주문이 남았고, 서버는 uid 10001로 돌았다.
