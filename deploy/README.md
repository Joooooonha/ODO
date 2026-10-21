# 배포 실행 순서 (odostudio.site)

도메인·외부 서비스 등록 같은 콘솔 준비 작업은 끝났다고 보고,
여기는 **서버에서 실행하는 순서**만 적는다.

처음에는 EC2 한 대에 nginx·Spring·PostgreSQL을 모두 두었고(아래 0~9절이 그 순서다),
2026-10에 채팅을 넣으며 **앱 2대 + DB 1대 + Redis 1대**로 나눴다(맨 아래 "운영 구성").

```mermaid
flowchart LR
    U["브라우저"] -->|HTTPS| CF["Cloudflare"]
    CF -->|"HTTPS (Full strict)"| A1
    subgraph VPC["AWS VPC · 같은 가용 영역"]
        A1["앱-1 EC2<br/>nginx + Spring"]
        A2["앱-2 EC2<br/>Spring"]
        DB[("PostgreSQL EC2")]
        RD[("Redis EC2<br/>Pub/Sub")]
        A1 -->|"사설망 8080"| A2
        A1 --> DB
        A2 --> DB
        A1 <--> RD
        A2 <--> RD
    end
    U -->|"presigned PUT·GET"| R2["Cloudflare R2"]
    A1 -.->|Tailscale| MM["맥미니<br/>FastAPI 분석 · Prometheus·Grafana"]
    A2 -.->|Tailscale| MM
    MM <-->|"영상·결과"| R2
```

영상 파일은 **EC2를 지나가지 않는다.** 브라우저가 R2로 직접 올리고 맥미니가
R2에서 직접 받는다. EC2는 권한 판단과 URL 발급만 한다.

---

## 0. EC2 인스턴스 준비

**기존 `finder-server`(t4g.small, 2GB)와 합치지 않고 새 인스턴스를 쓴다.**

| 이유 | 설명 |
|---|---|
| 메모리 | Spring 1GB + Postgres 300MB + OS 300MB ≈ **1.6GB**. 2GB에 다른 앱까지 얹으면 안 들어간다 |
| 격리 | 이 프로젝트는 앞으로 계속 재배포·설정 변경을 한다. Spring이 OOM나면 finder-server도 같이 죽는다 |
| 다운타임 | 수직 확장은 중지/시작이 필요해 finder-server가 1~2분 멈춘다 |

**스펙**

| 항목 | 값 |
|---|---|
| 타입 | `t4g.small` (2 vCPU / 2GB, ARM) — 부족하면 `t4g.medium`으로 확장 |
| 리전 | `ap-northeast-2` (서울, 기존과 동일) |
| AMI | **Ubuntu 22.04/24.04 arm64** |
| 스토리지 | gp3 20GB |
| Elastic IP | **할당해서 연결** — 없으면 재시작 시 IP가 바뀌어 DNS가 깨진다 |

> `t4g.small`로 충분한 이유: 무거운 일은 전부 옮겼다. 포즈 추출은 맥미니,
> 영상 파일은 R2 직접. EC2는 JSON 처리와 권한 판단만 한다.

> ⚠️ **ARM(Graviton)이다.** 설치하는 모든 바이너리를 `arm64`로 받아야 한다.
> jar 자체는 아키텍처 독립이라 그대로 쓸 수 있지만, JDK·Nginx·Postgres는
> arm64 패키지여야 한다(apt는 자동으로 맞춰준다).

**보안 그룹**

| 포트 | 허용 |
|---|---|
| 443, 80 | Cloudflare IP 대역 (또는 우선 0.0.0.0/0) |
| 22 | 내 IP만 |
| **8080, 5432** | **열지 않는다** — Nginx와 localhost로만 접근 |

### 패키지 설치 (ARM / Ubuntu)

```bash
sudo apt update && sudo apt upgrade -y

# Java 17 (arm64). Amazon Corretto 또는 OpenJDK 둘 다 가능.
sudo apt install -y openjdk-17-jre-headless
java -version   # 17.x, aarch64 확인

sudo apt install -y nginx postgresql postgresql-contrib

# 아키텍처 확인 — aarch64여야 한다
uname -m
```

### Tailscale (EC2)

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4      # 맥미니에서 이 주소로 접근하지는 않지만 기록해 둔다
```

## 0-1. 나머지 사전 조건

- [ ] Cloudflare에서 odostudio.site가 **Active**
- [ ] R2 버킷 + API 토큰 + CORS 설정
- [ ] 카카오 / 네이버 앱 등록
- [ ] 맥미니에 Tailscale 설치 (공식 pkg — App Store 버전 아님), IP 확인

---

## 1. Cloudflare Origin Certificate

**Full (strict)를 쓰려면 origin에도 인증서가 필요하다.** 없으면 526 에러가 난다.

Cloudflare → SSL/TLS → **Origin Server** → `Create Certificate`
(기본값 RSA / 15년). 화면에 뜬 인증서와 개인키를 EC2에 넣는다.

```bash
sudo mkdir -p /etc/ssl/cloudflare
sudo vi /etc/ssl/cloudflare/odostudio.site.pem   # Origin Certificate 붙여넣기
sudo vi /etc/ssl/cloudflare/odostudio.site.key   # Private Key 붙여넣기

# 개인키는 root만 읽을 수 있어야 한다
sudo chmod 600 /etc/ssl/cloudflare/odostudio.site.key
sudo chown root:root /etc/ssl/cloudflare/odostudio.site.*
```

> 이 인증서는 Cloudflare만 신뢰한다. 브라우저가 EC2에 직접 접속하면 경고가 뜨는데,
> 정상이다 — 모든 접속은 Cloudflare를 거쳐야 한다.

> ⚠️ **순서를 지킬 것.** 인증서를 EC2에 넣기 **전에** Cloudflare를 Full (strict)로
> 바꾸면, 검증할 인증서가 없어서 그 사이 사이트가 526으로 죽는다.

키를 넣은 **뒤에** Cloudflare SSL/TLS 모드를 **Full (strict)** 로 바꾼다.
순서를 뒤집으면 그 사이 사이트가 죽는다.

---

## 2. Nginx

```bash
sudo cp deploy/nginx/odostudio.site.conf /etc/nginx/sites-available/
sudo ln -sf /etc/nginx/sites-available/odostudio.site.conf /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default

sudo nginx -t && sudo systemctl reload nginx
```

---

## 3. PostgreSQL

프로세스는 **하나만** 띄우고 데이터베이스만 나눈다. 기존 앱이 쓰는 DB가 이미 있으면
그 인스턴스에 데이터베이스를 추가한다 — 프로세스를 하나 더 띄우면 메모리만 낭비다.

```bash
sudo -u postgres psql <<'SQL'
CREATE DATABASE dance_db;
CREATE USER dance WITH ENCRYPTED PASSWORD '여기에_강한_비밀번호';
GRANT ALL PRIVILEGES ON DATABASE dance_db TO dance;
\c dance_db
GRANT ALL ON SCHEMA public TO dance;
SQL
```

> 외부에 포트를 열지 않는다. `listen_addresses = 'localhost'` 확인.

---

## 3-1. Redis (채팅 서버 간 전달)

채팅 메시지를 서버끼리 넘기는 Pub/Sub에만 쓴다. 영속 데이터가 없어 저장(RDB·AOF)을 끈다.
서버 안(127.0.0.1)에서만 받고, 비밀번호를 건다. 메모리는 작게(64MB) 잡는다 — t4g.small은 2GB 중
약 1.6GB를 이미 쓴다. Docker가 아니라 apt로 설치한다.

**앱보다 먼저 준비한다.** 앱은 기동할 때 Redis에 PING을 보내고, 실패하거나 `REDIS_*`가 빠져 있으면
기동하지 않는다 → 배포 기동 확인 실패 → 직전 jar로 자동 롤백(직전 jar는 Redis를 쓰지 않는다).

**비밀번호는 서버 안에서 만들고 파일 두 곳에만 쓴다.** 로컬에서 만들어 명령줄로 넘기지 않는다
(셸 기록·프로세스 목록에 남는다). 아래 스크립트는 따옴표 친 heredoc(`<<'REMOTE'`)이라 로컬에서는
아무것도 펼쳐지지 않는다. 확인할 때도 `redis-cli -a` 대신 `REDISCLI_AUTH` 환경 변수를 쓴다 — 명령줄
인수가 아니라서 `ps`에 보이지 않는다. 이미 `REDIS_PASSWORD`가 있으면 새로 만들지 않는다(다시 돌려도 안전).

```bash
ssh odo 'sudo bash -s' <<'REMOTE'
set -euo pipefail
free -m | head -2
apt-get update -q && apt-get install -y -q redis-server
redis-server --version

if ! grep -q '^REDIS_PASSWORD=' /etc/dance-api.env; then
  PW="$(openssl rand -hex 32)"
  install -m 640 -o redis -g redis /dev/null /etc/redis/odo.conf
  cat > /etc/redis/odo.conf <<CONF
bind 127.0.0.1 -::1
protected-mode yes
port 6379
requirepass ${PW}
maxmemory 64mb
maxmemory-policy noeviction
save ""
appendonly no
CONF
  printf 'REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nREDIS_PASSWORD=%s\n' "$PW" >> /etc/dance-api.env
  unset PW
fi
# 기본 redis.conf 맨 끝에서 덮어쓴다(뒤에 오는 설정이 이긴다)
grep -qxF 'include /etc/redis/odo.conf' /etc/redis/redis.conf \
  || echo 'include /etc/redis/odo.conf' >> /etc/redis/redis.conf

systemctl enable redis-server
systemctl restart redis-server
REDISCLI_AUTH="$(sed -n 's/^REDIS_PASSWORD=//p' /etc/dance-api.env)" redis-cli -h 127.0.0.1 ping   # → PONG
redis-cli -h 127.0.0.1 ping || true    # → NOAUTH (비밀번호 없이는 거부)
ss -ltnp | grep 6379                   # 127.0.0.1·::1에서만
REMOTE
```

- `printf`·`echo`는 셸 내장 명령이라 비밀번호가 프로세스 목록에 나타나지 않는다. `/etc/dance-api.env`는
  처음 만들 때처럼 600(ubuntu 소유)으로 남는다. `/etc/redis/odo.conf`는 640(redis 소유)이다.
- nginx 웹소켓 블록(`location = /api/ws`)도 2절 방법으로 반영한다.

systemd 유닛(`deploy/systemd/dance-api.service`)의 `After=`에 `redis-server.service`를 더했다. 유닛 파일은
배포가 반영하지 않으므로 직접 바꾼다. **`daemon-reload`를 빠뜨리면 systemd가 예전 유닛을 계속 쓴다.**
바뀐 순서는 다음 재시작(배포)부터 적용된다.

```bash
scp deploy/systemd/dance-api.service odo:/tmp/
ssh odo 'sudo cp /tmp/dance-api.service /etc/systemd/system/ && sudo systemctl daemon-reload \
  && systemctl show -p After dance-api | tr " " "\n" | grep -E "redis|postgres"'   # redis-server.service가 보이면 반영됨
```

배포 뒤 확인:

```bash
ssh odo 'journalctl -u dance-api --since "10 min ago" | grep -E "Redis 기동 확인|채팅 지표"'
# 웹소켓 경로가 Spring까지 가는지: 쿠키 없이 → 401이면 정상. 200(HTML)이면 nginx가 화면으로 보낸 것
curl -s -o /dev/null -w '%{http_code}\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
     -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://odostudio.site/api/ws
```

브라우저 개발자 도구 → 네트워크에서 `/api/ws`가 **101**로 뜨는지 본다(로그인한 상태).

---

## 4. 비밀값

```bash
sudo install -m 600 -o ubuntu /dev/null /etc/dance-api.env
sudo vi /etc/dance-api.env
```

```bash
# 소셜 로그인
KAKAO_CLIENT_ID=...
KAKAO_CLIENT_SECRET=...
NAVER_CLIENT_ID=...
NAVER_CLIENT_SECRET=...

# 저장소 (R2)
R2_BUCKET=odostudio-media
R2_ENDPOINT=https://<계정ID>.r2.cloudflarestorage.com
R2_ACCESS_KEY=...
R2_SECRET_KEY=...

# 인증 — openssl rand -base64 48 로 생성
JWT_SECRET=...

# 분석 서버 (맥미니 Tailscale 주소)
AI_SERVER_URL=http://100.x.x.x:8000

# DB
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dance_db
SPRING_DATASOURCE_USERNAME=dance
SPRING_DATASOURCE_PASSWORD=...
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
```

> **이 파일에만 키를 둔다.** 저장소에는 절대 커밋하지 않는다
> (`application-secret.properties`도 gitignore에 있다).

### merge 전 확인: 운영 필수 값이 모두 있는지

운영 프로필은 아래 값에 기본값이 없다. 하나라도 비어 있으면 앱이 뜨지 않고(`ProdSecretsCheck`) 배포가
직전 jar로 자동 롤백된다. 특히 `JWT_SECRET`은 예전에는 빠져도 저장소에 공개된 개발용 키로 떠서 토큰 위조가
가능했다. **main에 merge하기 전에** 확인한다. 값은 출력하지 않고 이름과 있음·없음만 보여 준다.

```bash
ssh odo 'sudo bash -s' <<'REMOTE'
for name in JWT_SECRET KAKAO_CLIENT_ID KAKAO_CLIENT_SECRET NAVER_CLIENT_ID NAVER_CLIENT_SECRET \
            REDIS_HOST REDIS_PORT REDIS_PASSWORD AI_SERVER_URL SPRING_DATASOURCE_URL; do
  if grep -qE "^${name}=.+" /etc/dance-api.env; then echo "있음  ${name}"; else echo "없음  ${name}  ← 채워야 함"; fi
done
REMOTE
```

`없음`이 하나라도 있으면 채운 뒤 merge한다(`REDIS_*`는 3-1 스크립트가 넣는다).

---

## 4-1. 알림 (Web Push)

브라우저를 닫아도 분석 완료 알림이 뜨게 하려면 VAPID 키 한 쌍이 필요하다.
공개키는 브라우저에 내려주고, 개인키로 서명해서 푸시 서비스가 발신자를 확인한다.

**키 만들기** (한 번만, 로컬에서):

```bash
openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem
```

```bash
python3 -c "
import base64, subprocess, re
t = subprocess.run(['openssl','ec','-in','vapid.pem','-text','-noout'], capture_output=True, text=True).stdout
g = lambda a,b: bytes.fromhex(re.sub(r'[^0-9a-f]','', t.split(a)[1].split(b)[0]))
b = lambda x: base64.urlsafe_b64encode(x).decode().rstrip('=')
print('PUSH_VAPID_PUBLIC_KEY=' + b(g('pub:','ASN1 OID')))
print('PUSH_VAPID_PRIVATE_KEY=' + b(g('priv:','pub:')[-32:]))
"
```

두 줄을 `/etc/dance-api.env`에 넣는다. **개인키는 비밀이다** — 저장소에 올리지 말 것.
키를 설정하지 않으면 알림만 조용히 꺼지고 나머지는 정상 동작한다(기동 시 경고 한 줄).

`vapid.pem`은 키를 뽑은 뒤 지운다.

**iOS 주의**: 사파리는 홈 화면에 추가한 경우에만 웹 푸시를 허용한다.
그래서 `public/manifest.webmanifest`와 아이콘이 함께 있어야 한다.

---

## 5. 빌드 & 업로드

로컬(맥)에서 빌드한다. 작은 EC2에서 Gradle을 돌리면 메모리 부족으로 죽거나
빌드 도중 서비스가 같이 느려진다.

```bash
./deploy/build.sh

scp deploy/out/app.jar              odo:/tmp/app.jar
rsync -av --delete deploy/out/dist/ odo:/tmp/dist/
```

EC2에서:

```bash
sudo mkdir -p /opt/dance-api /var/www/odostudio
sudo mv /tmp/app.jar /opt/dance-api/app.jar
sudo rsync -av --delete /tmp/dist/ /var/www/odostudio/dist/
```

---

## 6. Spring 서비스 등록

```bash
sudo cp deploy/systemd/dance-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now dance-api

journalctl -u dance-api -f
```

> **첫 기동은 실패한다.** `ddl-auto=validate`인데 테이블이 없기 때문이다.
> 최초 1회만 스키마를 만들고 되돌린다:
>
> ```bash
> sudo systemctl stop dance-api
> sudo -u ubuntu env $(cat /etc/dance-api.env | xargs) \
>   SPRING_PROFILES_ACTIVE=prod SPRING_JPA_HIBERNATE_DDL_AUTO=update \
>   java -jar /opt/dance-api/app.jar   # 뜨면 Ctrl+C
> sudo systemctl start dance-api
> ```
>
> 운영에서 `validate`를 쓰는 이유는 앱이 운영 DB 스키마를 마음대로 바꾸지
> 못하게 하기 위해서다. 컬럼 추가가 필요하면 그때만 의도적으로 푼다.

---

## 7. 맥미니 분석 서버

**환경**: Fedora Linux Asahi Remix 44 (Apple Silicon 위 Linux, **aarch64**), 8코어 / 16GB

> 맥미니지만 macOS가 아니다. launchd가 아니라 **systemd**를 쓴다.

### 7-1. 패키지 (sudo 필요, 한 번만)

```bash
ssh macmini 'sudo dnf install -y python3.10 python3.10-devel ffmpeg-free'
```

| 패키지 | 왜 필요한가 |
|---|---|
| `python3.10` | 기본 파이썬이 3.14인데 **torch 휠이 cp310~cp313까지만** 있다 |
| `ffmpeg-free` | ⚠️ **빠뜨리면 조용히 망가진다** (아래) |

> **ffmpeg-free가 없으면**: 영상 회전 메타데이터를 `ffprobe`로 읽는데, 없으면
> 세로 촬영 영상이 **옆으로 누운 채** 분석된다. 에러가 나지 않고 결과만 나빠진다.
>
> | | ffprobe 없음 | 있음 |
> |---|---|---|
> | 연습 영상 관절 유효율 | 0.809 | **0.910** |
> | 평균 오차 | 31.4도 | **26.3도** |
> | 채점률 | 64.9% | **84.5%** |
>
> 지금은 코드가 경고를 출력하고 배포 스크립트가 배포를 막지만, 원인을 알기
> 어려운 종류의 고장이라 여기 남긴다.

### 7-2. 배포

```bash
./deploy/deploy-ai-server.sh
```

코드를 rsync로 보내고 venv·의존성을 설치한다. `git clone`을 쓰지 않는 이유는
원격 저장소가 아직 작업 이전 커밋이기 때문이다. 커밋이 정리되면 clone으로 바꿔도 된다.

### 7-3. 서비스 등록

```bash
ssh macmini '
  mkdir -p ~/.config/systemd/user
  # 바인딩 주소(Tailscale)는 저장소가 아니라 환경 파일에 둔다
  [ -f ~/.config/dance-ai.env ] || echo "AI_BIND_HOST=$(tailscale ip -4)" > ~/.config/dance-ai.env
  cp ~/dance-ai/../deploy/systemd/dance-ai.service ~/.config/systemd/user/ 2>/dev/null || true
  systemctl --user daemon-reload
  systemctl --user enable --now dance-ai
'
# 로그인하지 않아도 부팅 후 자동 시작 (이게 없으면 재부팅 시 안 뜬다)
ssh macmini 'sudo loginctl enable-linger $(whoami)'
```

### 7-3-1. 방화벽 (Fedora firewalld)

Fedora Server는 firewalld가 기본으로 켜져 있어, 서비스가 떠도 **다른 기기에서
접근되지 않는다.** Tailscale 인터페이스가 속한 존에만 포트를 연다.

```bash
# tailscale0가 어느 존에 있는지 확인
ssh macmini 'firewall-cmd --get-zone-of-interface=tailscale0'
# → coco-management

ssh macmini '
  sudo firewall-cmd --zone=coco-management --add-port=8000/tcp --permanent
  sudo firewall-cmd --reload
'
```

> **`public` 존이나 `--add-port`를 존 지정 없이 쓰지 말 것.** 그러면 집 공유기
> 네트워크에도 열려서, 같은 와이파이의 아무 기기나 분석을 호출할 수 있다.
> 이 서버에는 인증이 없다.

확인:
```bash
ssh macmini 'systemctl --user status dance-ai --no-pager | head -5'
curl -m 5 http://<맥미니 Tailscale IP>:8000/      # EC2나 맥북에서

# 바인딩이 Tailscale 주소에만 잡혔는지 (0.0.0.0이면 안 된다)
ssh macmini 'ss -tln | grep 8000'
```

> **0.0.0.0으로 열지 않는다.** 이 서버에는 인증이 없고 접근 통제를
> "네트워크에서 닿을 수 없다"에 의존한다. Tailscale 주소로만 바인딩한다.

### 7-4. 성능 (실측, CPU 전용)

Asahi Linux에는 **MPS도 CUDA도 없어 순수 CPU로 돈다.**

| 모델 | 프레임당 | 36초 영상 2개 분석 |
|---|---|---|
| `yolov8n-pose` | 0.097s | 약 200초 |
| `yolo11n-pose` | 0.091s | 약 170초 |
| `yolo11x-pose` | 2.068s | **약 74분 — 사용 불가** |

`yolo11x`는 뼈 안정성 1위지만 CPU에서 21배 느려 서비스에 쓸 수 없다
(맥북 MPS에서는 4.6배 차이였다). 가속기가 생기면 재검토한다.

## 8. DNS 연결

Cloudflare → DNS → 레코드 추가

| 타입 | 이름 | 값 | 프록시 |
|---|---|---|---|
| A | `@` | EC2 퍼블릭 IP | **프록시됨(주황 구름)** |
| A | `www` | EC2 퍼블릭 IP | 프록시됨 |

> 회색 구름(DNS only)으로 두면 EC2 IP가 그대로 노출되고 Cloudflare를 우회할 수 있다.

**EC2 보안 그룹**: 443/80은 Cloudflare IP 대역만, 22는 내 IP만.
**8080과 5432는 절대 열지 않는다.**

---

## 9. 확인

```bash
curl -I https://odostudio.site                      # 200, 프론트
curl -s -o /dev/null -w '%{http_code}\n' \
     https://odostudio.site/practice-logs           # 401 (인증 필요 — 정상)
curl -sI https://odostudio.site/oauth2/authorization/kakao | head -3   # 302 → kauth.kakao.com
```

브라우저에서 카카오/네이버 로그인 → 영상 업로드 → 구간 지정 → 분석까지 한 번 돌려본다.

---

## 되돌리기

```bash
# 이전 jar을 남겨두면 즉시 롤백할 수 있다
sudo cp /opt/dance-api/app.jar /opt/dance-api/app.jar.bak   # 배포 전에
sudo cp /opt/dance-api/app.jar.bak /opt/dance-api/app.jar   # 롤백
sudo systemctl restart dance-api
```

---

## 운영 점검 (로그·상태 보기)

문제가 생겼을 때 **어디를 보는지**가 절반이다. 요청 하나는 브라우저 → Cloudflare →
nginx → Spring → (Tailscale) → FastAPI 순서로 흐르므로, 그 순서대로 좁혀 나간다.

### 접속

```bash
ssh odo        # EC2 (Spring, PostgreSQL, nginx)
ssh macmini    # 맥미니 (FastAPI 분석 서버)
```

`ssh ubuntu@odostudio.site`는 **안 된다** (`No route to host`). 도메인은 Cloudflare
프록시 IP로 해석되고 Cloudflare는 SSH를 중계하지 않는다. `~/.ssh/config`의 별칭을 쓴다.

### 살아 있는지

```bash
ssh odo 'systemctl is-active dance-api nginx postgresql'   # 세 줄 모두 active
ssh macmini 'systemctl --user is-active dance-ai'          # active
```

맥미니는 `--user` 서비스라 `sudo systemctl`로는 안 보인다.

### 로그

```bash
ssh odo 'sudo journalctl -u dance-api -n 100 --no-pager'   # 최근 100줄
ssh odo 'sudo journalctl -u dance-api -f'                  # 실시간 (Ctrl+C로 종료)
ssh odo 'sudo journalctl -u dance-api --since "10 min ago" -p warning'  # 경고 이상만

ssh macmini 'journalctl --user -u dance-ai -n 100 --no-pager'
```

**시간대가 서로 다르다.** EC2는 UTC, 맥미니는 KST(+9). 같은 분석이 EC2 `05:28` /
맥미니 `14:28`로 찍힌다. 두 로그를 맞춰 볼 때 9시간을 더하거나 뺀다.

정상적인 분석 한 건은 양쪽에 이렇게 남는다:

```
# EC2 (Spring)
[비동기 분석 시작] PracticeLog ID: 1
[Spring -> 분석서버] 구간: 기준 3.49~null, 연습 6.54~null
[분석서버 -> Spring] 지적 구간 50개, 채점률 78.8%
[비동기 분석 완료] PracticeLog ID: 1, 지적 구간: 50개

# 맥미니 (FastAPI)
[분석 요청] 영상 다운로드 중...
[이미지] 상위 10개 구간 렌더링...
[분석 완료] 지적 구간 50개, 채점률 78.8%
INFO: ... "POST /analyze HTTP/1.1" 200 OK
```

Spring 쪽 시작 로그만 있고 완료가 없으면 맥미니를 본다. 맥미니에 요청 자체가 안
찍혔으면 Tailscale 경로 문제다 (`ssh odo 'tailscale status'`).

**돌고 있는 분석이 어디쯤인지**는 로그를 기다리지 않고 바로 볼 수 있다:

```bash
ssh macmini 'curl -s "http://$(tailscale ip -4):8000/progress"'
```

```
{"state":"running","job_id":"log-1-1788...","stage":"extract_practice",
 "label":"내 영상 분석 중","pct":46.0,"next_pct":89.0,"elapsed_sec":95.1}
```

`state`가 `running`인데 `elapsed_sec`이 400초를 넘으면 뭔가 걸린 것이다.
`idle`이면 아무 분석도 돌고 있지 않다 — Spring이 아직 요청을 못 보냈거나,
이미 끝났는데 Spring이 결과를 저장하지 못한 것이다.

### HTTP 레벨 (누가 무엇을 요청했나)

```bash
ssh odo 'sudo tail -30 /var/log/nginx/access.log'
ssh odo 'sudo tail -30 /var/log/nginx/error.log'
ssh odo "sudo awk '\$9 >= 400' /var/log/nginx/access.log | tail -20"   # 4xx/5xx만
```

`access.log`는 상태 코드가 9번째 필드다. `502`면 Spring이 죽은 것,
`404`인데 경로가 `/api/`로 시작하면 라우팅 문제다.

### DB

```bash
ssh odo 'sudo -u postgres psql -d dance_db -c "\dt"'                  # 테이블 목록
ssh odo 'sudo -u postgres psql -d dance_db -c "\d practice_log"'      # 스키마
ssh odo 'sudo -u postgres psql -d dance_db -c \
  "select result_id, log_id, status, issue_count, analyzed_date from analysis_result;"'
```

DB 이름은 **`dance_db`**다(`dance`가 아니라). 소유자 롤이 `dance`.

`@Lob String` 필드(`feedback_content`, `top_issues_json`, `quality_json` …)는
PostgreSQL에서 **`oid`(Large Object)로 매핑**돼 그냥 select하면 숫자 하나만 나온다.
내용을 보려면:

```bash
ssh odo "sudo -u postgres psql -d dance_db -At -c \
  \"select convert_from(lo_get(top_issues_json),'UTF8') from analysis_result where result_id=1;\""
```

### 자원

```bash
ssh odo 'uptime; free -h; df -h /'
ssh macmini 'uptime; free -h; df -h /'
```

EC2는 메모리 1.8GB뿐이라 `free`의 **free 열은 늘 작다** — 봐야 할 것은
`available` 열과 swap 사용량이다. 분석은 맥미니에서만 도므로 EC2 load는 보통 0에 가깝다.

---

## 운영 구성 (2026-10~: 앱 2대 + DB 1대 + Redis 1대)

채팅 서버를 두 대로 늘리면서 DB와 Redis를 앱 서버 밖으로 뺐다. 설계 판단은
[채팅 딥다이브](../docs/chat-deep-dive.md)에 있고, 여기는 운영에 필요한 원칙만 적는다.

### 네트워크

| 대상 | 들어오는 길 | 이유 |
|---|---|---|
| 앱-1 443/80 | Cloudflare IP 대역만 | 원 서버 주소로 Cloudflare를 우회하지 못하게 |
| 앱 8080 | 앱 보안 그룹끼리만(앱-1 nginx → 앱-2) | Spring은 인터넷에 직접 열지 않는다 |
| DB 5432, Redis 6379 | 앱 보안 그룹에서만 | 공인 IP로는 열지 않는다. Redis는 비밀번호 + 사설 IP 바인딩 |
| SSH | 앱-1을 거쳐(ProxyJump) 사설망으로 | DB·Redis·앱-2는 인터넷 쪽 SSH가 없다 |
| 관리 포트 9091·node_exporter 9100 | Tailscale ACL로 맥미니에서만 | 보안 그룹에는 열지 않는다. 앱 포트 8080에는 actuator가 없다 |

Tailscale ACL은 태그로 나눈다: `tag:ci → tag:server:22`(배포), `tag:server → 맥미니:8000`(분석),
`맥미니 → tag:server:9091,9100`(모니터링). DB·Redis 서버는 tailnet에 붙이지 않는다.

### 서버별 설정 (`/etc/dance-api.env`)

두 앱은 같은 jar·같은 비밀값을 쓰고, 아래 값만 다르다.

| 키 | 앱-1 | 앱-2 | 쓰임 |
|---|---|---|---|
| `SERVER_ADDRESS` | `127.0.0.1` | 자기 사설 IP | 앱-1 nginx가 사설망으로 앱-2에 들어온다 |
| `CHAT_SERVER_INDEX` / `CHAT_SERVER_COUNT` | `0` / `2` | `1` / `2` | 읽음 반영 주기를 서버마다 엇갈리게(0ms·250ms) |

nginx 서버 목록은 `/etc/nginx/dance-api-servers.conf`(예시: `nginx/dance-api-servers.conf.example`)에 둔다.
웹소켓은 어느 서버로 가도 된다 — 서버 사이 전달은 Redis Pub/Sub이 한다.

### 배포 (`scripts/deploy-hosts.sh`, GitHub Actions)

무중단 대신 **짧게 멈추는 방식**을 택했다(배포마다 약 1분). 옛 버전과 새 버전이 함께 돌며
스키마·메시지 형식이 섞이는 경우를 아예 없앤다.

1. 두 대 모두 멈춤 → 둘 다 jar 교체
2. 앱-1 먼저 기동(Flyway 마이그레이션은 한 서버에서만) → 확인 → 앱-2 기동 → 확인
3. 하나라도 실패하면 **두 대 모두** 직전 jar로 되돌린다

### 함께 바뀐 코드

| 바뀐 것 | 이유 |
|---|---|
| 소셜 로그인 인가 요청을 서명된 쿠키에 저장 | 시작과 콜백이 다른 서버로 가도 로그인이 된다(세션에 두면 실패) |
| 분석 요청은 PostgreSQL advisory lock으로 전체 동시 1건 | 맥미니 한 대가 처리하므로 서버가 늘어도 동시 분석 수는 그대로 |

### 백업과 켜고 끄기

- DB 디스크는 EBS 수명 주기 정책으로 매일 스냅샷(7개 보관) + 필요할 때 `pg_dump -Fc`
- 켜는 순서: DB·Redis → 앱-1 → 앱-2. 앱은 기동 때 DB·Redis에 닿지 않으면 실패하고, systemd가 10초 뒤 다시 띄운다(5분에 5번까지)
- 끄는 순서는 반대. 사설 IP는 껐다 켜도 유지되므로 설정을 다시 쓸 필요가 없다

### 모니터링 (`monitoring/`)

맥미니의 Prometheus가 두 앱 서버의 `/actuator/prometheus`(관리 포트 9091, `prod,monitoring` 프로필)와
node_exporter를 Tailscale로 15초마다 긁고, Grafana 대시보드 "채팅 운영"으로 본다.
Prometheus·Grafana는 맥미니의 127.0.0.1에만 열고 노트북에서는 SSH 터널로 본다.

