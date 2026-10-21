# 운영 분산 구성 반영 절차서 (앱 2대 + DB 1대 + Redis 1대)

10/21에 정한 구성(준하 결정)으로 옮기는 절차다. 이 문서의 **1부는 준하가 직접 하는 일**(AWS 콘솔, Tailscale, GitHub, 운영 서버 명령)이고, **2부는 저장소 변경**(Claude Code가 이 브랜치에서 했다)이다. 오늘 할 순서는 "오늘(10/21) 순서 한눈에"를 먼저 본다.

## 0. 정한 것

| 항목 | 결정 | 메모 |
|---|---|---|
| 순서 | **(가)** PR #4(채팅)를 지금의 한 대 구성으로 먼저 반영 → 그다음 분산으로 이전 | 1단계는 `deploy/chat-read-rollout.md` 그대로(Redis는 일단 지금 서버 안) |
| 앱 서버 | EC2 2대: 지금 서버(odo, 앱-1) + 새 서버(앱-2), 둘 다 t4g.small | |
| DB | **별도 EC2**(odo-db, t4g.small, PostgreSQL 16) | 백업은 EBS 스냅샷(매일) + pg_dump |
| Redis | **별도 EC2**(odo-redis, t4g.micro) — 10/21 확정 | 사설 IP + 비밀번호, 앱 보안 그룹에서만 |
| 앞단 분배 | 지금 nginx — **앱-1(odo)에 둔다** — 10/21 확정 | Cloudflare 인증서·DNS 그대로 |
| 일정 | **오늘(10/21) 1단계와 2단계를 이어서**, 자정 전 마무리 | 아래 "오늘 순서" |
| 맥미니 | 두 앱 서버 모두 Tailscale로 | 분석 동시 1건은 코드로 지킨다(2부) |
| 배포 | 서비스를 잠깐 멈추는 방식: 두 대 멈춤 → 둘 다 교체 → 앱-1 먼저 기동(Flyway) → 앱-2 기동 → 실패하면 둘 다 되돌림 | 배포마다 약 1분 중단, 옛 버전과 새 버전이 섞이지 않는다 |

### 0-1. (확정) 두 가지를 정한 근거

**Redis를 맥미니에 두는 것은 권하지 않는다 → 별도 EC2(t4g.micro, 같은 VPC)를 권한다.**

| | 맥미니(집, Tailscale) | 별도 EC2 t4g.micro(같은 VPC) |
|---|---|---|
| 앱 ↔ Redis 왕복 | Tailscale 직접 연결이면 대략 5~15ms, 중계(DERP)면 20~50ms(측정 안 함) | 1ms 미만 |
| 메시지 처리에 주는 영향 | 메시지마다 발행이 처리 스레드 안에서 동기로 일어난다(측정: 0.2~0.5ms). 왕복 10ms를 더하면 1건이 약 4ms → 14ms다. 스레드 8개면 서버당 처리 상한이 약 2,000 → 570건/초로 준다. 전달 지연도 왕복만큼 는다 | 지금 측정과 같다 |
| 끊길 때 | 집 전원·인터넷·공유기·Tailscale 중 하나만 멈춰도 채팅 실시간 전달이 멈춘다. **앱은 기동할 때 Redis PING이 실패하면 뜨지 않는다** → 맥미니가 꺼진 동안은 배포도 실패한다 | AWS 안이라 앱과 같은 수준 |
| 프로세스 분리 | 된다 | 된다(앱·DB와 다른 장비) |
| 비용 | 0 | 약 $8 + 공인 IP $3.6 + 디스크 $0.7 ≈ 월 $12 |

→ "트래픽 처리 때 프로세스 사이 영향이 없게"라는 목적은 **별도 EC2로도 그대로 이룬다.** 지연과 가용성 문제가 없어서 별도 EC2로 정했다(10/21).

**앞단 nginx는 앱-1(odo)에 둔다(맥미니는 권하지 않음).** 맥미니에 두면 모든 사용자 요청이 집 인터넷을 지난다. Cloudflare가 집으로 들어오는 길도 따로 열어야 한다. 앱-1에 두면 지금 인증서, Elastic IP, Cloudflare DNS를 **하나도 바꾸지 않는다.**

## 시작 전에 적어 둘 값 (명령의 `<…>` 자리)

| 값 | 어디서 | 쓰는 곳 |
|---|---|---|
| 앱-1 사설 IP | 단계 2(EC2 → odo → 세부 정보) | 8-2 pg_hba |
| 앱-2·DB·Redis 사설 IP | 단계 4에서 만든 뒤 각 인스턴스 세부 정보 | 8-1 SSH 설정, 8-2, 8-6, nginx 서버 목록 |
| 키 페어 이름 / 맥의 키 파일 | 단계 2 | 단계 4, 8-1 |
| 앱-2 Tailscale 주소 | 8-4 끝에 출력됨(`100.x.y.z`) | 8-7 `DEPLOY_HOSTS` |
| 앱-1 Tailscale 주소 | `100.114.82.112`(10/21 확인) | 8-7 `DEPLOY_HOSTS` 첫 칸 |
| Tailscale 인증 키 | 단계 6-3(비밀번호 관리자에만) | 8-4 입력 프롬프트 |

## 미리 확인한 것 (10/21, 운영·AWS 밖에서)

| 확인 | 방법 | 결과 |
|---|---|---|
| 배포 스크립트(`deploy-hosts.sh`) | 가짜 서버(폴더) 두 대 + 가짜 ssh·systemctl·curl로 실제 스크립트를 돌림 | ① 두 대 정상 ② 앱-2만 실패 ③ 첫 서버 실패 ④ 멈추는 단계에서 앱-2 접속 안 됨 ⑤ 한 대 — 모두 기대대로(성공은 jar·화면 교체, 실패는 두 대 다 직전 jar·화면, ④는 jar를 바꾸지 않고 앱-1만 다시 띄움) |
| nginx 설정 | 공식 이미지 nginx 1.24 + 시험용 인증서로 `nginx -t` | 서버 목록 한 대·두 대·예시 파일 모두 통과. 목록 파일이 없으면 `nginx -t`에서 멈춘다(돌고 있는 nginx는 그대로) |
| DB 옮기기(8-6 ②) | PostgreSQL 16 컨테이너 두 개에 V1~V4 스키마 + 데이터(사용자·채팅방·메시지·큰 객체·Flyway 이력)를 넣고 절차서 명령 그대로 | 행 수·Flyway 4·큰 객체 대조 같음, 테이블·큰 객체 소유자 모두 dance, 큰 객체 읽힘, 새 행 번호가 이어짐 |
| 비밀값 도우미 | Ubuntu 24.04·PostgreSQL 16 컨테이너 | 따옴표·공백이 든 값, 다시 돌리기, 빈 값·이상한 키 거절 |
| 로그인 쿠키 크기 | 테스트 | 2,144바이트(브라우저 한도 4,096 안), 테스트에 검사를 넣음 |

## 월 비용 어림 (서울 리전 온디맨드, 결정 전에 AWS 요금 계산기로 확인)

| 장비 | 사양 | 월 |
|---|---|---|
| 앱-2 | t4g.small + gp3 20GB + 공인 IPv4 | 약 $15 + $1.8 + $3.6 ≈ $20 |
| odo-db | t4g.small + gp3 20GB + 공인 IPv4(설치·업데이트용, 들어오는 길은 닫음) | 약 $20 |
| odo-redis | t4g.micro + gp3 8GB + 공인 IPv4 | 약 $12 |
| EBS 스냅샷 | DB 디스크 매일, 7개 보관(변경분만 저장) | $1 미만 |
| **추가 합계** | | **약 $40~53** |

---

# 1부 — 준하가 하는 일 (순서대로)

**표기:** `[클릭]` 화면에서 누름, `[선택]` 목록에서 고름, `[입력]` 값을 적음. AWS 콘솔 오른쪽 위 리전이 **아시아 태평양(서울) ap-northeast-2**인지 매번 확인한다. 콘솔 화면 문구는 바뀔 수 있다. 아래는 2026년 기준 일반적인 이름이다.

## 단계 1. PR #4(채팅)를 한 대 구성으로 반영 — 분산 작업보다 먼저

`deploy/chat-read-rollout.md`를 처음부터 끝까지 한다.

1. 0절의 읽기 전용 확인. 10/21에 이미 했다: V3까지, 팀 1, 활동 멤버 2, Redis 없음, nginx 768/1024
2. 1절 Redis(지금 서버 안), 2절 nginx 연결 상한, 3절 systemd
3. 4절 PR #4 병합(main) → GitHub Actions "배포 (EC2)"가 초록인지 확인
4. 5절 배포 뒤 확인

이게 며칠 안정적으로 돈 뒤에 단계 2로 간다.

## 단계 2. 지금 서버의 네트워크 정보 적어 두기 (콘솔, 읽기만)

1. [클릭] AWS 콘솔 → 위 검색창에 `EC2` → **EC2**
2. [클릭] 왼쪽 **인스턴스** → 목록에서 지금 서버를 고른다(공인 IP `13.125.143.96`)
3. 아래 **세부 정보** 탭에서 적어 둔다:
   - **VPC ID**(`vpc-…`), **서브넷 ID**(`subnet-…`), **가용 영역**(예: `ap-northeast-2a`), **프라이빗 IPv4 주소**(예: `172.31.x.x`)
   - **키 페어 이름**(새 서버도 같은 키를 쓴다)
4. **보안** 탭에서 **보안 그룹** 이름(예: `launch-wizard-1`)을 적어 둔다
5. (백업 확인) 왼쪽 **Elastic Block Store → 스냅샷**에 이 서버 디스크의 스냅샷이 있는지 본다. 없으면 지금은 DB 백업이 없는 상태다(서버 안 cron에도 없음, 10/21 확인)

## 단계 3. 보안 그룹 만들기

**원칙:** DB(5432)와 Redis(6379)는 앱 서버의 보안 그룹에서만 들어온다. 공인 IP로는 열지 않는다. 앱의 8080은 앱 보안 그룹끼리만 연다(앱-1 nginx → 앱-2).

### 3-1. 앱 보안 그룹 `odo-app`

1. [클릭] EC2 → 왼쪽 **네트워크 및 보안 → 보안 그룹** → 오른쪽 위 **보안 그룹 생성**
2. [입력] 보안 그룹 이름 `odo-app`, 설명 `chat app servers (8080 between apps)`
3. [선택] VPC: 단계 2에서 적은 VPC
4. 인바운드 규칙은 **비워 둔 채** [클릭] **보안 그룹 생성**(자기 자신을 소스로 쓰려면 먼저 만들어야 한다)
5. 만든 `odo-app`을 열고 [클릭] **인바운드 규칙 편집 → 규칙 추가**
   - 유형 `사용자 지정 TCP`, 포트 범위 `8080`, 소스 `사용자 지정` → 검색칸에 `odo-app`을 입력해 **자기 자신(sg-…)** 선택, 설명 `app-1 nginx -> app-2`
   - [클릭] **규칙 추가** → 유형 `SSH`, 소스 `odo-app`(자기 자신), 설명 `ssh app-1 -> app-2 (ProxyJump)`
   - [클릭] **규칙 저장**
6. 아웃바운드는 기본값(모두 허용) 그대로

### 3-2. DB 보안 그룹 `odo-db`

1. **보안 그룹 생성** → 이름 `odo-db`, 설명 `postgres from app servers only`, VPC 같음
2. 인바운드 규칙 추가:
   - 유형 `PostgreSQL`(포트 5432 자동), 소스 `사용자 지정` → `odo-app` 선택, 설명 `apps`
   - 유형 `SSH`, 소스 `사용자 지정` → `odo-app`, 설명 `ssh via app-1 (ProxyJump)`
3. **보안 그룹 생성**

> SSH는 인터넷이나 "내 IP"에 열지 않는다. 맥 → 앱-1(odo) → DB로 건너서 들어간다(`ProxyJump`, 단계 8-1). 나중에 지울 규칙도 없다.

### 3-3. Redis 보안 그룹 `odo-redis`

1. 이름 `odo-redis`, 설명 `redis from app servers only`
2. 인바운드:
   - `사용자 지정 TCP` 6379, 소스 `odo-app`
   - `SSH`, 소스 `odo-app`(앱-1을 거쳐 들어감)
3. **보안 그룹 생성**

### 3-4. 지금 서버(앱-1)에 `odo-app` 붙이기

1. EC2 → **인스턴스** → 지금 서버 선택 → 위 **작업 → 보안 → 보안 그룹 변경**
2. [입력] 검색칸에 `odo-app` → [클릭] **보안 그룹 추가** → 기존 그룹(80·443·22)은 **그대로 둔 채** 두 개가 되게 → [클릭] **저장**
   - 서비스에는 영향이 없다(규칙을 더할 뿐)

## 단계 4. 새 EC2 만들기

공통: [클릭] EC2 → **인스턴스 → 인스턴스 시작**

### 4-1. DB 서버 `odo-db`

1. **이름 및 태그**: [입력] 이름 `odo-db` → [클릭] **추가 태그 추가** → 키 `Backup`, 값 `daily`(단계 5의 스냅샷 정책이 이 태그를 찾는다)
   - 태그 대상은 **인스턴스와 볼륨** 둘 다 체크
2. **애플리케이션 및 OS 이미지**: [선택] **Ubuntu** → **Ubuntu Server 24.04 LTS** → 아키텍처 **64비트(Arm)**
3. **인스턴스 유형**: [선택] `t4g.small`
4. **키 페어**: [선택] 단계 2에서 적은 키 페어
5. **네트워크 설정** → [클릭] **편집**
   - VPC: 같은 VPC. 서브넷: **단계 2와 같은 가용 영역의 서브넷**(같은 AZ끼리는 전송 요금이 없다)
   - 퍼블릭 IP 자동 할당: **활성화**(apt 설치·업데이트용 나가는 길. 들어오는 길은 보안 그룹이 막는다)
   - 방화벽(보안 그룹): **기존 보안 그룹 선택** → `odo-db`
6. **스토리지 구성**: [입력] `20` GiB, [선택] `gp3`
7. 오른쪽 **요약** 확인 → [클릭] **인스턴스 시작**
8. 만들어지면 인스턴스를 열어 **프라이빗 IPv4 주소**를 적어 둔다(앱의 DB 주소가 된다)

### 4-2. 앱 서버 `odo-app-2`

4-1과 같되:

- 이름 `odo-app-2`(Backup 태그는 붙이지 않음)
- 인스턴스 유형 `t4g.small`
- 보안 그룹: **기존 보안 그룹 선택** → `odo-app` 하나(SSH는 앱-1을 거쳐 들어가고, 배포는 Tailscale로 한다)
- 퍼블릭 IP 자동 할당: **활성화**(R2·소셜 로그인·apt로 나가는 길)
- 스토리지 20GiB gp3

### 4-3. Redis 서버 `odo-redis`

4-1과 같되:

- 이름 `odo-redis`, 인스턴스 유형 `t4g.micro`, 스토리지 `8` GiB gp3
- 보안 그룹 `odo-redis`
- 만든 뒤 프라이빗 IPv4 주소를 적어 둔다

## 단계 5. DB 디스크 매일 스냅샷 (EBS 수명 주기 정책)

1. [클릭] EC2 → 왼쪽 **Elastic Block Store → 수명 주기 관리자** → **수명 주기 정책 생성**
2. [선택] **EBS 스냅샷 정책** → **다음**
3. 대상 리소스 유형 **볼륨**, 대상 리소스 태그 키 `Backup`, 값 `daily` → [클릭] **추가**
4. [입력] 정책 설명 `odo-db daily snapshot`. IAM 역할은 **기본 역할**
5. 일정:
   - 빈도 **매일**, 매 **24시간**, 시작 시간 `18:00` UTC(= 03:00 KST)
   - 보존 유형 **개수**, `7`
6. [클릭] **정책 생성** → 상태가 **활성화됨**인지 본다
7. (선택) 지금 서버(앱-1)의 볼륨에도 같은 `Backup=daily` 태그를 붙이면 이전 전까지 지금 DB도 매일 스냅샷된다
   - EC2 → **볼륨** → 앱-1 볼륨 선택 → **태그 → 태그 관리 → 태그 추가**

## 단계 6. Tailscale

### 6-1. 지금 ACL 보기

1. [클릭] https://login.tailscale.com/admin → 위 **Access controls**
2. 지금 정책을 복사해 둔다(되돌리기용). `"acls"`가 모두 허용(`"*"`)인지, deploy.yml 안내처럼 `tag:ci → odo-ec2:22`만 있는지 확인한다

### 6-2. 태그와 규칙 더하기

정책에 아래를 **더한다**(기존 내용은 지우지 않는다. 형식은 지금 정책에 맞춘다):

```jsonc
"tagOwners": {
  "tag:ci":     ["autogroup:admin"],
  "tag:server": ["autogroup:admin"]
},
"acls": [
  // 배포: GitHub 러너 → 앱 서버 두 대 SSH
  { "action": "accept", "src": ["tag:ci"],     "dst": ["tag:server:22"] },
  // 분석: 앱 서버 → 맥미니 분석 서버
  { "action": "accept", "src": ["tag:server"], "dst": ["coco-mac-mini:8000"] }
]
```

- 맥미니 분석 포트가 8000이 아니면 `AI_SERVER_URL`의 포트로 바꾼다(값은 서버의 `/etc/dance-api.env`에 있다)
- [클릭] **Save** → 오류가 없으면 반영된다

### 6-3. 새 서버를 붙일 인증 키

1. [클릭] **Settings → Keys → Generate auth key**
2. [선택] Reusable **끔**, Expiration `1 day`, Ephemeral **끔**, Tags **켬** → `tag:server`
3. [클릭] **Generate key** → 키를 **비밀번호 관리자에만** 붙여 둔다(채팅이나 파일에 적지 않는다)
4. 앱-2에서만 쓴다(8-4). DB·Redis 서버는 Tailscale에 붙이지 않는다(사설망과 앱-1을 거친 SSH만)
5. 앱-1(odo-ec2)에도 태그를 붙인다: **Machines** → `odo-ec2` → **⋯ → Edit ACL tags** → `tag:server` 추가 → Save

## 단계 7. GitHub 배포 비밀값

1. [클릭] GitHub 저장소 → **Settings → Secrets and variables → Actions**
2. [클릭] **New repository secret** — **8-6 전환을 마친 뒤**(8-7)에 만든다. 먼저 만들면 9-1 배포가 아직 준비 안 된 앱-2까지 가려 한다
   - 이름 `DEPLOY_HOSTS`
   - 값: 앱 서버 두 대의 Tailscale 주소를 공백으로 띄워 적는다. 예: `100.114.82.112 100.x.y.z`(앱-2 주소는 단계 6 뒤 `tailscale ip -4`로 확인)
   - 기존 `DEPLOY_HOST`는 새 배포가 자리 잡을 때까지 지우지 않는다
3. `DEPLOY_KNOWN_HOSTS`를 [클릭] **Update** → 기존 줄 아래에 앱-2의 호스트 키 줄을 더한다
   - 줄은 앱-2에서 `cat /etc/ssh/ssh_host_ed25519_key.pub`로 얻는다(공개키라 비밀이 아니다)
   - 형식 `100.x.y.z ssh-ed25519 AAAA…`
   - `ssh-keyscan`으로 밖에서 받지 않는다(중간자를 막지 못한다)

## 단계 8. 서버 명령

모든 명령은 **맥 터미널**에서 친다. `<…>`는 단계 2·4에서 적어 둔 값으로 바꾼다. 비밀값은 화면에 나오지 않게 서버끼리 파이프로 넘긴다(맥은 지나가기만 한다).

### 8-1. 맥의 SSH 설정 (중단 없음)

`~/.ssh/config`에 더한다. 새 서버 셋 다 **앱-1(odo)을 거쳐** 사설 IP로 들어간다. 키 파일은 지금 `odo`에 쓰는 것과 같다(단계 2의 키 페어).

```
Host odo-app2
    HostName <앱-2 사설 IP>
    User ubuntu
    ProxyJump odo
Host odo-db
    HostName <DB 사설 IP>
    User ubuntu
    ProxyJump odo
Host odo-redis
    HostName <Redis 사설 IP>
    User ubuntu
    ProxyJump odo
```

- `odo` 항목에 `IdentityFile`이 있으면 셋에도 같은 줄을 넣는다
- **확인:** `ssh odo-db hostname; ssh odo-redis hostname; ssh odo-app2 hostname` → 각 서버 이름이 나온다
  - 처음 접속 때 호스트 키 지문을 묻는다. EC2 콘솔 → 인스턴스 → **작업 → 모니터링 및 문제 해결 → 시스템 로그 가져오기**의 `ec2: ... SHA256:...`과 같은지 보고 `yes`
- **되돌리기:** 추가한 줄을 지운다

도우미 스크립트를 세 서버와 앱-1에 올린다:

```bash
for h in odo odo-db odo-redis odo-app2; do scp deploy/scripts/set-env-key.sh deploy/scripts/pg-create-dance.sh deploy/scripts/db-rowcounts.sql $h:/tmp/; done
```

### 8-2. DB 서버 준비 (중단 없음, 약 10분)

```bash
ssh odo-db 'sudo bash -s' <<'REMOTE'
set -euo pipefail
apt-get update -q && apt-get install -y -q postgresql-16
IP="$(hostname -I | awk '{print $1}')"
cat > /etc/postgresql/16/main/conf.d/odo.conf <<CONF
listen_addresses = 'localhost,${IP}'
password_encryption = scram-sha-256
CONF
systemctl restart postgresql
REMOTE
# 앱-1의 지금 DB 비밀번호를 그대로 쓴다(앱 설정은 주소만 바꾸면 된다). 값은 화면에 나오지 않는다
ssh odo 'sudo sed -n "s/^SPRING_DATASOURCE_PASSWORD=//p" /etc/dance-api.env' | ssh odo-db 'sudo bash /tmp/pg-create-dance.sh'
# 앱 두 대의 사설 IP에서만, 비밀번호로만 들어오게
ssh odo-db 'sudo bash -s' <<'REMOTE'
set -euo pipefail
HBA=/etc/postgresql/16/main/pg_hba.conf
cp -a $HBA $HBA.bak-odo
for ip in <앱-1 사설 IP> <앱-2 사설 IP>; do
  grep -q "dance_db dance $ip/32" $HBA || echo "host dance_db dance $ip/32 scram-sha-256" >> $HBA
done
systemctl reload postgresql
REMOTE
```

- **확인**
  - `ssh odo-db 'sudo ss -ltn | grep 5432'`: 127.0.0.1과 사설 IP만 보이고 0.0.0.0은 없다
  - `ssh odo 'pg_isready -h <DB 사설 IP> -p 5432'`: accepting connections
  - 앱-1에서 비밀번호로 접속되는지(값은 보이지 않음): `ssh odo 'PGPASSWORD="$(sudo sed -n "s/^SPRING_DATASOURCE_PASSWORD=//p" /etc/dance-api.env)" psql -h <DB 사설 IP> -U dance -d dance_db -Atc "select 1"'`(앱-1에 psql이 있다) → `1`
- **되돌리기:** 아직 아무도 쓰지 않는다. 인스턴스를 지우면 끝

### 8-3. Redis 서버 준비 (중단 없음, 약 5분)

```bash
ssh odo-redis 'sudo bash -s' <<'REMOTE'
set -euo pipefail
apt-get update -q && apt-get install -y -q redis-server
IP="$(hostname -I | awk '{print $1}')"
if [ ! -f /etc/redis/odo.conf ]; then
  install -m 640 -o redis -g redis /dev/null /etc/redis/odo.conf
  cat > /etc/redis/odo.conf <<CONF
bind 127.0.0.1 ${IP}
protected-mode yes
port 6379
requirepass $(openssl rand -hex 32)
maxmemory 128mb
maxmemory-policy noeviction
save ""
appendonly no
CONF
fi
grep -qxF 'include /etc/redis/odo.conf' /etc/redis/redis.conf || echo 'include /etc/redis/odo.conf' >> /etc/redis/redis.conf
systemctl enable redis-server && systemctl restart redis-server
REMOTE
```

- **확인**
  - `ssh odo-redis 'REDISCLI_AUTH="$(sudo sed -n "s/^requirepass //p" /etc/redis/odo.conf)" redis-cli ping'`: PONG
  - `ssh odo-redis 'redis-cli ping'`: NOAUTH
  - `ssh odo-redis 'sudo ss -ltn | grep 6379'`: 127.0.0.1과 사설 IP만
  - 앱-1에서 닿는지(보안 그룹): `ssh odo 'timeout 3 bash -c "</dev/tcp/<Redis 사설 IP>/6379" && echo 열림'`
- **되돌리기:** 인스턴스를 지우면 끝

### 8-4. 앱-2 준비 (중단 없음, 약 15분)

```bash
ssh odo-app2 'sudo bash -s' <<'REMOTE'
set -euo pipefail
apt-get update -q && apt-get install -y -q openjdk-17-jre-headless postgresql-client
curl -fsSL https://tailscale.com/install.sh | sh
install -d -o ubuntu -g ubuntu /opt/dance-api
REMOTE
# Tailscale 인증 키(단계 6-3): 입력해도 화면에 나오지 않는다. 파일로 넘기고 바로 지운다
ssh -t odo-app2 'read -rsp "Tailscale auth key: " K; echo; umask 077; printf "%s" "$K" > /tmp/ts.key; sudo tailscale up --auth-key=file:/tmp/ts.key --hostname=odo-app-2; rm -f /tmp/ts.key; tailscale ip -4'
# 앱-1과 같은 jar, 같은 유닛, 같은 비밀값(서버끼리 복사, 화면에 나오지 않음)
ssh odo 'cat /opt/dance-api/app.jar' | ssh odo-app2 'cat > /opt/dance-api/app.jar && cp /opt/dance-api/app.jar /opt/dance-api/app.jar.bak'
scp deploy/systemd/dance-api.service odo-app2:/tmp/ && ssh odo-app2 'sudo cp /tmp/dance-api.service /etc/systemd/system/ && sudo systemctl daemon-reload'
ssh odo 'sudo cat /etc/dance-api.env' | ssh odo-app2 'sudo install -m 600 -o ubuntu -g ubuntu /dev/stdin /etc/dance-api.env'
# 앱-2만의 값: 자기 사설 IP에 바인딩(앱-1 nginx가 사설망으로 들어온다), 서버 순번 1
ssh odo-app2 'hostname -I | awk "{print \$1}"' | ssh odo-app2 'sudo bash /tmp/set-env-key.sh SERVER_ADDRESS'
echo 1 | ssh odo-app2 'sudo bash /tmp/set-env-key.sh CHAT_SERVER_INDEX'
echo 2 | ssh odo-app2 'sudo bash /tmp/set-env-key.sh CHAT_SERVER_COUNT'
```

- **아직 띄우지 않는다**(`systemctl enable`만 하고 start는 8-6에서). DB·Redis 주소가 아직 앱-1 안을 가리킨다
  - `ssh odo-app2 'sudo systemctl enable dance-api'`
- **확인**
  - `ssh odo-app2 'java -version 2>&1 | head -1; ls -l /opt/dance-api; sudo grep -oE "^[A-Z0-9_]+=" /etc/dance-api.env | sort | tr "\n" " "'`: 키 이름만 본다. 앱-1과 같은 키에 SERVER_ADDRESS·CHAT_SERVER_* 3개가 더 있다
  - 맥미니에 닿는지: `ssh odo-app2 'tailscale ping -c 2 coco-mac-mini'`
  - 맥미니 분석 서버: `ssh odo-app2 'curl -s -o /dev/null -w "%{http_code}\n" "$(sudo sed -n "s/^AI_SERVER_URL=//p" /etc/dance-api.env)/progress"'`: 200(주소는 화면에 나오지 않는다)
  - Tailscale 관리 화면 **Machines**에 `odo-app-2`가 `tag:server`로 보인다
- **되돌리기:** `ssh odo-app2 'sudo tailscale logout'` 뒤 인스턴스를 지운다

### 8-5. 앱-1 nginx를 "서버 목록 파일" 방식으로 (중단 없음, 1분)

`feature/distributed-ops`의 사이트 설정은 서버 목록을 `/etc/nginx/dance-api-servers.conf`에서 읽는다. **파일을 먼저 만들고**(한 대) 설정을 바꾼다.

```bash
scp deploy/nginx/odostudio.site.conf deploy/nginx/dance-api-servers.conf.example odo:/tmp/
ssh odo 'sudo bash -s' <<'REMOTE'
set -euo pipefail
[ -f /etc/nginx/dance-api-servers.conf ] || echo "server 127.0.0.1:8080;" > /etc/nginx/dance-api-servers.conf
cp -a /etc/nginx/sites-available/odostudio.site.conf /etc/nginx/sites-available/odostudio.site.conf.bak-dist
cp /tmp/odostudio.site.conf /etc/nginx/sites-available/odostudio.site.conf
nginx -t && systemctl reload nginx
REMOTE
```

- **확인:** `curl -sI https://odostudio.site | head -1` → 200. `ssh odo 'sudo nginx -T 2>/dev/null | grep -A3 "upstream dance_api"'`
- **되돌리기:** `ssh odo 'sudo cp -a /etc/nginx/sites-available/odostudio.site.conf.bak-dist /etc/nginx/sites-available/odostudio.site.conf && sudo nginx -t && sudo systemctl reload nginx'`

### 8-6. 전환 — **서비스 중단 약 10분**

이 단계 전에 **`feature/distributed-ops`가 main에 병합돼 앱-1에 배포돼 있어야 한다**(단계 9-1: 쿠키 로그인·분석 잠금이 든 jar). 그 jar를 앱-2로도 복사한다(8-4의 jar 복사를 한 번 더).

```bash
ssh odo 'cat /opt/dance-api/app.jar' | ssh odo-app2 'cat > /opt/dance-api/app.jar && cp /opt/dance-api/app.jar /opt/dance-api/app.jar.bak'
```

**① 앱-1 멈춤(여기서부터 중단)**

```bash
ssh odo 'sudo cp -a /etc/dance-api.env /etc/dance-api.env.bak-single && sudo systemctl stop dance-api'
```

**② DB 옮기기**(8.4MB라 수 초)

```bash
ssh odo 'cd /tmp && sudo -u postgres pg_dump -Fc -d dance_db -f /tmp/dance_db.dump && sudo -u postgres psql -d dance_db -XAt -f /tmp/db-rowcounts.sql > /tmp/rowcounts-old.txt && ls -l /tmp/dance_db.dump'
ssh odo 'sudo cat /tmp/dance_db.dump' | ssh odo-db 'cat > /tmp/dance_db.dump'
ssh odo-db 'cd /tmp && sudo -u postgres pg_restore --no-owner --role=dance -d dance_db /tmp/dance_db.dump && sudo -u postgres psql -d dance_db -XAt -f /tmp/db-rowcounts.sql > /tmp/rowcounts-new.txt'
diff <(ssh odo 'cat /tmp/rowcounts-old.txt') <(ssh odo-db 'cat /tmp/rowcounts-new.txt') && echo "✅ 행 수·Flyway·큰 객체 같음"
```

- diff가 나오면 **멈추고 되돌린다**(아래 되돌리기)

**③ 두 앱의 설정을 새 DB·Redis로**(값은 화면에 나오지 않음)

```bash
for h in odo odo-app2; do
  echo "jdbc:postgresql://<DB 사설 IP>:5432/dance_db" | ssh $h 'sudo bash /tmp/set-env-key.sh SPRING_DATASOURCE_URL'
  echo "<Redis 사설 IP>" | ssh $h 'sudo bash /tmp/set-env-key.sh REDIS_HOST'
  echo 6379 | ssh $h 'sudo bash /tmp/set-env-key.sh REDIS_PORT'
  ssh odo-redis 'sudo sed -n "s/^requirepass //p" /etc/redis/odo.conf' | ssh $h 'sudo bash /tmp/set-env-key.sh REDIS_PASSWORD'
  echo 2 | ssh $h 'sudo bash /tmp/set-env-key.sh CHAT_SERVER_COUNT'
done
echo 0 | ssh odo 'sudo bash /tmp/set-env-key.sh CHAT_SERVER_INDEX'
# (단계 10을 같이 할 때) 두 앱에 모니터링 프로필 — ④에서 띄울 때 적용된다
for h in odo odo-app2; do echo "prod,monitoring" | ssh $h 'sudo bash /tmp/set-env-key.sh SPRING_PROFILES_ACTIVE'; done
```

**④ 띄우기: 앱-1 먼저, 그다음 앱-2**

```bash
ssh odo 'sudo systemctl start dance-api'; sleep 20
ssh odo 'curl -sf -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/push/public-key'          # 200
ssh odo-app2 'sudo systemctl start dance-api'; sleep 20
ssh odo-app2 'curl -sf -o /dev/null -w "%{http_code}\n" "http://$(hostname -I | awk "{print \$1}"):8080/api/push/public-key"'   # 200
ssh odo 'curl -sf -o /dev/null -w "%{http_code}\n" http://<앱-2 사설 IP>:8080/api/push/public-key'   # 200 (앱-1 → 앱-2 8080, 보안 그룹 확인)
```

**⑤ nginx를 두 대로(여기서 중단 끝)**

```bash
ssh odo 'sudo bash -s' <<'REMOTE'
set -euo pipefail
cp -a /etc/nginx/dance-api-servers.conf /etc/nginx/dance-api-servers.conf.bak-single
printf 'server 127.0.0.1:8080 max_fails=2 fail_timeout=10s;\nserver %s:8080 max_fails=2 fail_timeout=10s;\n' "<앱-2 사설 IP>" > /etc/nginx/dance-api-servers.conf
nginx -t && systemctl reload nginx
REMOTE
```

**⑥ 확인**

```bash
ssh odo 'journalctl -u dance-api --since "5 min ago" --no-pager | grep -E "Redis 기동 확인|읽음 모으기\] 켬"'        # 위상=0ms
ssh odo-app2 'journalctl -u dance-api --since "5 min ago" --no-pager | grep -E "Redis 기동 확인|읽음 모으기\] 켬"'   # 위상=250ms
ssh odo-redis 'REDISCLI_AUTH="$(sudo sed -n "s/^requirepass //p" /etc/redis/odo.conf)" redis-cli client list | wc -l'  # 앱 두 대의 연결
ssh odo-db 'sudo -u postgres psql -XAt -c "select client_addr, count(*) from pg_stat_activity where datname = '\''dance_db'\'' group by 1"'   # 앱 두 대의 사설 IP
```

- 브라우저 두 개(다른 계정)에서 로그인(카카오·네이버 각각 몇 번 — 시작과 콜백이 다른 서버로 가도 성공해야 한다) → 같은 팀 채팅방 → 메시지·읽음 표시가 서로 바로 보이는지
- 분석 하나를 실행해 끝까지 가는지(맥미니)

**되돌리기(전환 중 어디서든, 중단 약 2분)** — 새 DB에 쓴 데이터는 옛 DB에 없다. 전환 직후에만 쓴다

```bash
ssh odo-app2 'sudo systemctl stop dance-api'
ssh odo 'sudo systemctl stop dance-api; sudo cp -a /etc/dance-api.env.bak-single /etc/dance-api.env; \
  [ -f /etc/nginx/dance-api-servers.conf.bak-single ] && sudo cp -a /etc/nginx/dance-api-servers.conf.bak-single /etc/nginx/dance-api-servers.conf; \
  sudo nginx -t && sudo systemctl reload nginx; sudo systemctl start dance-api'
```

앱-1의 옛 PostgreSQL과 Redis(1단계에서 설치)는 그대로 있어서 바로 돌아간다.

### 8-7. 배포를 두 대로 (중단 약 1분)

1. 단계 7의 GitHub 비밀값 `DEPLOY_HOSTS`(앱-1 Tailscale 주소 먼저, 다음 앱-2)와 `DEPLOY_KNOWN_HOSTS`(앱-2 줄 추가)
2. GitHub → **Actions → 배포 (EC2) → Run workflow**(main) → 로그에 `✅ <서버> 기동 확인`이 두 번, `✅ 모든 서버 배포 끝`
3. 실패하면 스크립트가 두 대 모두 직전 jar로 되돌린다. 로그의 `❌`·`⚠️` 줄을 본다

### 8-8. 정리 (며칠 뒤, 중단 없음)

- 앱-1 안의 옛 PostgreSQL·Redis 끄기: `ssh odo 'sudo systemctl disable --now postgresql redis-server'`(데이터 디렉터리는 지우지 않는다)
- `odo-db` 볼륨 스냅샷이 매일 생기는지: EC2 → 스냅샷
- `deploy/README.md`의 운영 점검 절에 "두 대 모두 보기"(journalctl을 odo·odo-app2에서)

## 단계 10. 모니터링 (맥미니 Prometheus·Grafana, 약 30분)

구성: 앱 서버 두 대가 **관리 포트 9091**(`/actuator/prometheus`, `prod,monitoring` 프로필일 때만)과 **node_exporter 9100**(CPU·메모리·디스크)을 연다. 맥미니의 Prometheus가 **Tailscale로** 15초마다 긁고, Grafana 대시보드 "채팅 운영"으로 본다.

- 9091·9100은 보안 그룹에 열지 않는다 → 인터넷·사설망에서는 닿지 않고, Tailscale ACL로 맥미니에서만 닿는다
- 앱 포트 8080(nginx가 넘기는 곳)에는 actuator가 없다(`MonitoringProfileTest`로 확인)
- Prometheus(127.0.0.1:9090)와 Grafana(127.0.0.1:3000)는 맥미니 밖으로 열지 않는다 → 노트북에서는 SSH 터널로 본다
- DB·Redis 서버 지표는 이번 범위가 아니다(Tailscale에 붙이지 않았다). 필요하면 나중에 앱-1을 거쳐 긁는다

### 10-1. Tailscale ACL (콘솔, 중단 없음)

단계 6-2의 정책에 더한다:

```jsonc
"hosts": { "coco-mac-mini": "100.117.201.13" },
"acls": [
  // 모니터링: 맥미니 → 앱 서버 지표·자원
  { "action": "accept", "src": ["coco-mac-mini"], "dst": ["tag:server:9091,9100"] }
]
```

(`hosts`·`acls`가 이미 있으면 그 안에 항목만 더한다)

### 10-2. 앱 서버 두 대 (중단: 앱마다 재시작 약 20초 — 8-6 ③에서 같이 하면 추가 중단 없음)

```bash
for h in odo odo-app2; do
  ssh $h 'sudo apt-get install -y -q prometheus-node-exporter && systemctl is-active prometheus-node-exporter'
  echo "prod,monitoring" | ssh $h 'sudo bash /tmp/set-env-key.sh SPRING_PROFILES_ACTIVE'
done
# 8-6 ④보다 먼저 했다면 재시작이 필요 없다(④에서 띄운다). 이미 돌고 있다면 한 대씩:
ssh odo 'sudo systemctl restart dance-api'; sleep 25; ssh odo-app2 'sudo systemctl restart dance-api'
```

- **확인**(서버 안에서)
  - `ssh odo 'curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9091/actuator/prometheus; curl -s http://127.0.0.1:9091/actuator/health; curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9100/metrics'` → `200`, `{"status":"UP"}`, `200`
  - 밖에서는 닿지 않는지: `curl -s -m 5 -o /dev/null -w "%{http_code}\n" http://13.125.143.96:9091/actuator/prometheus` → `000`(시간 초과)
- **되돌리기:** `echo prod | ssh <서버> 'sudo bash /tmp/set-env-key.sh SPRING_PROFILES_ACTIVE'` 뒤 재시작, `sudo apt-get remove -y prometheus-node-exporter`

### 10-3. 맥미니에 Prometheus·Grafana (중단 없음)

맥미니에 컨테이너 도구가 있는지 먼저 본다(어느 하나):

```bash
ssh macmini 'docker compose version 2>/dev/null || podman compose version 2>/dev/null || echo "컨테이너 도구 없음"'
```

없으면 Fedora 기준 `ssh -t macmini 'sudo dnf install -y podman podman-compose'`(또는 Docker CE). 그다음:

```bash
cd ~/SeSAC/Project/dance-analyze-service && git fetch -q origin && git checkout -q feature/distributed-ops
rsync -a deploy/monitoring/ macmini:~/odo-monitoring/      # 다시 돌려도 targets.yml·비밀번호 파일은 그대로(저장소에 없다)
ssh macmini 'set -e; cd ~/odo-monitoring
  [ -f targets.yml ] || cp targets.example.yml targets.yml
  umask 077; [ -s grafana-admin-password ] || openssl rand -hex 16 > grafana-admin-password
  if docker compose version >/dev/null 2>&1; then docker compose up -d; else podman compose up -d; fi'
```

- `targets.yml`의 `odo-ec2`·`odo-app-2`가 맥미니에서 풀리는지: `ssh macmini 'getent hosts odo-ec2 odo-app-2'`
  - 안 풀리면 100.x 주소로 바꾼다(`tailscale status`의 주소)
- Grafana 관리자 비밀번호는 `ssh macmini 'cat ~/odo-monitoring/grafana-admin-password'` 로 한 번 보고 비밀번호 관리자에 넣는다

**확인**

```bash
ssh macmini 'curl -s 127.0.0.1:9090/api/v1/targets | grep -oE "\"(job|health)\":\"[a-z0-9]+\"" | paste - - | sort | uniq -c'
#   → app·node 각 2개, prometheus 1개가 모두 "health":"up"
ssh -N -L 3000:127.0.0.1:3000 macmini      # 이 창은 열어 둔다
#   브라우저 http://localhost:3000 → admin / (위 비밀번호) → 대시보드 "채팅 운영"
```

- 메시지 저장 지연·나간 프레임·발행 실패 같은 패널은 **채팅이 한 번 오간 뒤에** 선이 생긴다(처음 쓸 때 지표가 만들어진다)
- **되돌리기:** `ssh macmini 'cd ~/odo-monitoring && (docker compose down || podman compose down)'`

## 단계 9. 병합

### 9-1. `feature/distributed-ops`를 먼저 병합 — 아직 한 대 구성으로 (중단 약 30초)

- 전제: PR #4가 이미 main에 있다(단계 1). 그러면 이 PR의 차이는 이 브랜치의 커밋뿐이다
- **`DEPLOY_HOSTS`는 아직 만들지 않는다** → 배포는 예전처럼 `DEPLOY_HOST` 한 대(앱-1)로 간다
  - 새 코드(쿠키 로그인·분석 잠금·서버 목록 파일)는 한 대에서도 그대로 동작한다
- **단, 8-5(nginx 서버 목록 파일)를 병합 전에 끝내야 한다**
  - 이 PR의 사이트 설정 파일은 배포가 반영하지 않으니 바로 깨지지는 않는다
  - 하지만 이후 nginx 설정을 바꿀 때 파일이 없으면 `nginx -t`가 실패한다
- 확인: GitHub Actions "배포 (EC2)" 초록. 카카오·네이버 로그인을 한 번씩(쿠키 방식으로 바뀜). 분석 하나 실행

### 9-2. 그 밖

- Cloudflare DNS, 카카오·네이버 콘솔의 Redirect URI는 **바꾸지 않는다**(도메인과 앞단 서버가 그대로다)

## 오늘(10/21) 순서 한눈에

| 시각(예상) | 할 일 | 중단 |
|---|---|---|
| 지금 ~ | 단계 2~7 콘솔 작업(보안 그룹, EC2 3대, 스냅샷 정책, Tailscale, GitHub — `DEPLOY_HOSTS`만 나중에) | 없음 |
| 병행 | 단계 1: PR #4 반영(`chat-read-rollout.md`) | 약 30초 |
| 그다음 | 8-1~8-5(SSH 설정, DB·Redis·앱-2 준비, nginx 서버 목록 파일) | 없음 |
| 그다음 | 9-1: `feature/distributed-ops` 병합(한 대로 배포) | 약 30초 |
| 그다음 | 8-6 전환 | **약 10분** |
| 그다음 | 8-7 배포를 두 대로(Run workflow로 한 번 확인) | 약 1분 |
| 마지막 | 단계 10 모니터링(10-2를 8-6 ③에서 같이 하면 재시작 추가 없음) | 없음~앱마다 20초 |

## 비용 줄이기 — 필요할 때만 켜기

- **꺼도 나가는 돈**
  - EBS 디스크: 20GB × 3 + 8GB ≈ 월 $6
  - 공인 IPv4: 앱-1의 Elastic IP는 꺼 둬도 시간당 과금이다. 자동 할당 IP는 끄면 반납되어 과금이 멈추고, 켜면 새 주소를 받는다
  - 스냅샷
- **끄면 멈추는 돈:** 인스턴스 시간 요금
- **사설 IP는 껐다 켜도 그대로다** → DB·Redis 주소, nginx 서버 목록, 앱 설정을 바꿀 필요가 없다. 앱-2의 공인 IP가 바뀌어도 Tailscale·사설망만 쓰니 상관없다
- **켜는 순서:** odo-db, odo-redis → (1~2분) → 앱-1 → 앱-2
  - 앱은 기동 때 DB와 Redis를 확인한다. 먼저 뜨면 실패하고 10초 뒤 다시 시도한다(5분에 5번까지)
- **끄는 순서:** 앱-2 → 앱-1 → odo-redis, odo-db
- EC2 콘솔 → 인스턴스 → 네 대 선택 → **인스턴스 상태 → 중지/시작**. 꺼져 있는 동안 사이트는 Cloudflare 오류 화면(521)이 뜬다
- 매일 스냅샷 정책은 꺼진 동안에도 돈다(변경이 없으면 거의 0)

---

# 2부 — 저장소 변경 (Claude Code, 이 브랜치)

| 할 일 | 내용 | 테스트 |
|---|---|---|
| 소셜 로그인 인가 요청을 쿠키로 | `AuthorizationRequestRepository`를 서명된 쿠키 구현으로 바꾼다(시작과 콜백이 다른 서버로 가도 됨). 쿠키는 HttpOnly·Secure·SameSite=Lax, 수명 몇 분 | 시작 → 다른 인스턴스에서 콜백 |
| 분석 전체 동시 1건 | PostgreSQL advisory lock으로 두 서버 합쳐 1건만 맥미니에 보낸다. 다른 서버는 기다린다(지금 대기열 20 유지) | 두 실행기 동시 실행 |
| 서버별 설정 | `SERVER_ADDRESS`(앱-2는 사설 IP), `CHAT_SERVER_INDEX`·`CHAT_SERVER_COUNT`를 환경 변수 파일로. systemd 유닛의 고정 `SERVER_ADDRESS=127.0.0.1`을 env로 옮김 | 프로필 테스트 |
| nginx upstream | 앱-1 nginx에서 `127.0.0.1:8080` + `앱-2 사설IP:8080`. 웹소켓은 서버를 가리지 않는다(Redis로 전달). `max_fails`·`fail_timeout`으로 죽은 서버를 뺌 | `nginx -t` |
| deploy.yml | `DEPLOY_HOSTS`의 모든 서버: 멈춤 → 교체 → 앱-1 기동(Flyway) → 확인 → 나머지 기동 → 확인. 하나라도 실패하면 모두 `.bak`로 되돌림. 화면(dist)은 앱-1에만 | 문법 확인. 첫 실행은 준하가 지켜봄 |
| DB 이전 스크립트 | `pg_dump -Fc`(큰 객체 포함) → `pg_restore` → 테이블별 행 수·Flyway 이력 대조 | 측정 장비에서 연습 |
| 절차서 | 이 문서 단계 8에 명령·확인·되돌리기·중단 시간 | |
