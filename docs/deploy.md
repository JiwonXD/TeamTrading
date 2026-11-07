# 배포

`deploy/`에 서버 한 대에 올리는 묶음이 있다.

| 구성 | 역할 |
| --- | --- |
| `mysql` | MySQL 8.4. 데이터는 `mysql-data` 볼륨 |
| `backend` | 서버. `backend/Dockerfile`이 컨테이너 안에서 빌드한 이미지. 비루트 사용자로 실행. 8080(서비스)과 8081(관리)은 컨테이너 네트워크 안에서만 열린다 |
| `web` | nginx. `frontend/Dockerfile`이 서버 모드로 빌드한 화면을 주고 `/api`, `/ws`, 소셜 로그인 경로를 서버로 넘긴다. 바깥에 여는 포트는 이것 하나 |
| `prometheus` | 서버 지표 수집과 경보 규칙(`deploy/prometheus/alerts.yml`). 서버 안(127.0.0.1:9090)에서만 열린다 |
| `backup` | 매일 DB를 덤프해 `deploy/backups`에 남긴다 |

## 올리기

필요: Docker(Compose v2)만. 서버와 화면은 컨테이너 안에서 빌드하므로 Java·Node를 설치하지 않아도 된다. 첫 빌드는 의존성을 받느라 몇 분 걸린다.

```bash
cd deploy
cp .env.example .env      # DB 비밀번호, 소셜 로그인 키를 채운다
docker compose up -d --build
docker compose ps         # backend가 healthy가 되면 web이 뜬다
```

- 키 없이 먼저 시험하려면 `.env`에서 `SPRING_PROFILES_ACTIVE=demo`로 둔다. 닉네임 로그인과 시연용 가상 매매·경기가 켜진다. 운영에서는 `oauth`로 둔다.
- 운영자 지정: 운영할 계정으로 로그인한 뒤 `/api/accounts/me`의 `id`를 `ADMIN_ACCOUNT_IDS`에 넣고 `docker compose up -d backend`로 다시 띄운다.
- 공개 전에 `.env`의 `OPERATOR_NAME`, `OPERATOR_EMAIL`(개인정보 보호책임자와 문의 연락처)을 채우고 `docker compose up -d --build`로 다시 띄운다(화면 이미지에 들어간다). 이용약관·개인정보처리방침 화면에 들어간다. 저장소에는 넣지 않는다. 약관과 방침은 서비스 동작에 맞춘 초안이라 공개 전에 한 번 읽고 고칠 곳을 고친다(`frontend/src/pages/Legal.tsx`).
- 실제 경기 일정·결과(KBO·K리그)를 받으려면 API-Sports 무료 키를 `MATCH_FEED_API_KEY`에 넣고 구단과 제공처 팀 번호를 잇는다([운영 점검](operations.md#경기-데이터-수집)). 비워 두면 운영자가 경기를 직접 넣는다.
- 소셜 로그인 콜백 주소는 `https://{도메인}/login/oauth2/code/{kakao|naver|google}`이다. 서버는 nginx가 넘기는 `X-Forwarded-*`를 믿는다(`SERVER_FORWARD_HEADERS_STRATEGY=native`). 그래서 콜백 주소와 쿠키의 Secure 속성이 바깥 주소 기준으로 정해진다.

## 베타: 집 PC에서 돌리기

사용자가 적은 동안은 유료 서버 없이 집 PC에서 같은 묶음을 돌린다. 바깥 연결은 Cloudflare Tunnel(무료)로 한다. PC가 Cloudflare 쪽으로 연결을 거는 방식이다. 그래서 공유기 포트를 열지 않고, 집 IP도 드러나지 않으며, HTTPS 인증서도 Cloudflare가 붙인다.

### 도메인 없이 먼저 시험하기

도메인과 Cloudflare 계정 없이도 임시 주소로 바로 열 수 있다. 지인 테스트용이다. 다시 띄울 때마다 주소가 바뀌고, 주소가 고정되지 않아 소셜 로그인은 쓸 수 없으므로 `SPRING_PROFILES_ACTIVE=demo`(닉네임 로그인)로 둔다.

```bash
cd deploy
cp .env.example .env
sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$(openssl rand -hex 16)/; s/^DB_ROOT_PASSWORD=.*/DB_ROOT_PASSWORD=$(openssl rand -hex 16)/; s/^SPRING_PROFILES_ACTIVE=.*/SPRING_PROFILES_ACTIVE=demo/" .env
docker compose --profile quick-tunnel up -d --build
docker compose logs quick-tunnel | grep trycloudflare   # https://xxx.trycloudflare.com 주소가 나온다
```

- Windows에서는 Git Bash에서 그대로 실행한다. DB 비밀번호는 MySQL이 처음 뜰 때 볼륨에 들어가므로 그 뒤에는 바꾸지 않는다.
- `ports are not available ... 0.0.0.0:80 ... forbidden by its access permissions`가 나면 Windows가 80번을 다른 프로그램에 주었거나 예약해 둔 것이다. `.env`의 `HTTP_PORT`를 `8088`처럼 다른 번호로 바꾼다. 터널은 컨테이너 네트워크 안에서 `web:80`으로 가므로 PC 쪽 포트 번호와 상관없다. PC에서는 `http://localhost:8088`로 연다.

### 도메인으로 열기

1. Docker Desktop을 설치하고 "로그인 시 시작"을 켠다. Windows에서는 PowerShell에서 같은 명령을 쓴다(`cp` 대신 `copy`). `restore.sh`는 Git Bash나 WSL에서 실행한다.
2. 도메인(.kr 등)을 Cloudflare에 연결한다(네임서버 변경).
3. Cloudflare Zero Trust → Networks → Tunnels에서 터널을 만든다.
   - 커넥터 종류는 Docker를 고르고, 화면에 나오는 토큰을 `.env`의 `CLOUDFLARE_TUNNEL_TOKEN`에 넣는다.
   - Public Hostname에서 도메인을 서비스 `http://web:80`으로 연결한다.
4. 묶음과 터널을 함께 띄운다.
   ```bash
   cd deploy
   docker compose --profile tunnel up -d --build
   ```
5. 소셜 로그인 콜백 주소를 `https://{도메인}/login/oauth2/code/{kakao|naver|google}`로 등록한다. 키가 준비되기 전에는 `SPRING_PROFILES_ACTIVE=demo`로 닉네임 로그인만 열어 지인 테스트를 한다.

감수할 점:

- PC가 꺼지거나 잠자기에 들어가면 서비스도 멈춘다. 전원 설정에서 잠자기를 끈다. 컨테이너는 `restart: unless-stopped`라 Docker가 뜨면 다시 올라온다.
- 백업은 `backup` 서비스가 매일 뜬다. 다만 PC 디스크가 망가지면 덤프도 함께 사라지므로 `deploy/backups`를 PC 밖(클라우드 드라이브 동기화 폴더 등)에도 복사한다.
- 처리량의 상한은 집 업로드 속도와 PC 사양이다. 사용자가 늘면 같은 묶음을 클라우드 VM에 그대로 옮기고, Cloudflare의 Public Hostname만 새 서버로 바꾼다.

## HTTPS

nginx는 80번만 연다. TLS는 앞단에서 끝낸다.

- AWS: ALB에 ACM 인증서를 붙이고 대상 그룹을 이 서버의 80번으로 둔다. ALB가 `X-Forwarded-Proto: https`를 넘긴다.
- 서버 한 대: 앞에 Caddy를 두면 인증서 발급과 갱신을 자동으로 한다.

## 업데이트

```bash
git pull
docker compose -f deploy/docker-compose.yml up -d --build
```

서버는 종료 신호를 받으면 처리 중인 요청을 끝내고 내려간다(최대 30초). 기동할 때 DB에서 호가창을 다시 쌓는다. 그동안 들어온 주문은 nginx가 502로 돌려준다. 클라이언트는 같은 `clientOrderId`로 다시 보내면 된다. 강제 종료 중에도 장부가 맞는지는 `tools/chaos/run.sh`로 확인한다(운영 점검 문서 참고).

DB 스키마는 Flyway가 기동할 때 올린다. 이미 적용된 마이그레이션 파일은 고치지 않는다. 고치면 체크섬이 달라져 서버가 뜨지 않는다.

## 백업

`backup` 서비스가 매일 한국 시간 `BACKUP_AT`(기본 04:00)에 `mysqldump --single-transaction`으로 DB를 떠서 `deploy/backups/teamtrading-날짜-시각.sql.gz`로 남긴다.

- 서비스를 멈추지 않고 한 시점의 장부를 뜬다(InnoDB 트랜잭션 스냅숏). 원장·잔고·주문이 서로 맞는 상태로 남는다.
- 끝까지 쓰인 덤프만 이름을 붙여 남긴다. 실패하면 10초 간격으로 5분까지 다시 해 보고, 그래도 안 되면 다음 날 다시 한다.
- `BACKUP_KEEP_DAYS`(기본 14)일 지난 덤프는 지운다.
- 최근 26시간 안의 덤프가 없으면 `docker compose ps`에 backup이 `unhealthy`로 보인다. 처음 띄울 때는 바로 한 번 뜬다.
- 지금 바로 뜨려면:
  ```bash
  docker compose -f deploy/docker-compose.yml exec backup /usr/local/bin/backup.sh once
  ```
- 덤프는 같은 PC에 있으므로 다른 곳(클라우드 드라이브, S3 등)에도 복사한다.
- 관리형 DB(RDS 등)를 쓰면 자동 백업과 시점 복구를 켠다. 이때는 `mysql`·`backup` 서비스를 빼고 `backend`의 `SPRING_DATASOURCE_URL`을 그 DB 주소로 바꾼다.

### 복원

```bash
cd deploy
./restore.sh backups/teamtrading-20261102-040000.sql.gz
```

서버를 멈추고 DB를 비운 뒤 덤프를 넣고 서버를 다시 띄운다. 서버는 기동할 때 DB에서 호가창을 다시 쌓는다. 덤프 뒤의 주문·체결·포인트는 모두 사라지므로 '복원'을 입력해야 진행한다. 끝까지 쓰이지 않은 덤프는 거절한다. 복원하는 동안 화면의 요청은 502를 받는다.

CI(Deploy bundle smoke test)가 매번 덤프를 뜨고 복원해, 덤프 뒤에 생긴 계정만 사라지고 서버가 다시 뜨는지 확인한다.

## 확인한 것 (2026-11-02, 로컬 Docker)

- 네 컨테이너가 모두 healthy로 떴다.
- nginx를 거쳐 다음이 됐다: 화면, 시세 API, 로그인(CSRF 포함), 주문, WebSocket 실시간 시세 스냅숏.
- Prometheus가 서버 지표를 수집했고(`up`), 경보 규칙 4개가 로드됐다.
- 서버 컨테이너를 재시작해도 대기 주문이 남았고, 서버는 uid 10001로 돌았다.
