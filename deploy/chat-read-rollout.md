# 채팅(chat-core + chat-read) 운영 반영 절차서

`feature/chat-read`를 main에 병합하기 전후에 **운영 서버(odo, t4g.small 2 vCPU / 2GB, 서버 한 대)**에서 사람이 실행하는 순서다.
명령은 모두 맥에서 `ssh odo`로 보낸다. 측정 결과와 채택 근거는 `load-test/results/2026-10-21-improve-v3/README.md`("개선별 정리")에 있다.

> **순서가 중요하다.** main 병합 = 운영 배포다(`.github/workflows/deploy.yml`, main push 때 jar·화면을 올리고 재시작).
> 새 jar는 Redis가 없거나 `REDIS_*`가 빠지면 **기동하지 않는다**(ProdSecretsCheck, Redis PING). 그러면 배포가
> 직전 jar로 자동 롤백된다. 그래서 1~3단계를 **병합 전에** 끝낸다.

| 단계 | 언제 | 무엇 |
|---|---|---|
| 0 | 병합 전 | 읽기 전용 사전 확인 |
| 1 | 병합 전 | Redis 설치·설정, `/etc/dance-api.env`에 `REDIS_*` |
| 2 | 병합 전 | nginx `worker_connections`·`worker_rlimit_nofile`, 사이트 설정(웹소켓 블록) |
| 3 | 병합 전 | systemd 유닛 교체 + `daemon-reload`(재시작은 배포가 한다) |
| 4 | 병합 | PR 병합 → deploy.yml이 배포(Flyway V4 적용) |
| 5 | 병합 뒤 | 배포 뒤 확인 |
| 6 | 문제 시 | 되돌리기 |

---

## 0. 읽기 전용 사전 확인

```bash
ssh odo 'nproc; free -m | head -2; df -h / | tail -1'                       # 2, 메모리 여유, 디스크
ssh odo 'grep -nE "worker_(processes|connections|rlimit_nofile)" /etc/nginx/nginx.conf'
ssh odo 'systemctl is-active redis-server 2>/dev/null; redis-server --version 2>/dev/null || echo "redis 없음"'
ssh odo 'sudo grep -oE "^[A-Z0-9_]+=" /etc/dance-api.env | sort'               # 값은 보지 않고 키 이름만
ssh odo 'systemctl show -p After dance-api | tr " " "\n" | grep -E "redis|postgres"'
ssh odo "sudo -u postgres psql -d dance_db -Atc 'select version, success from flyway_schema_history order by installed_rank'"
ssh odo "sudo -u postgres psql -d dance_db -Atc 'select (select count(*) from team), (select count(*) from team_member where status = '\''ACTIVE'\'')'"
```

- Flyway 이력이 V3까지면 정상이다(V4가 이번 배포에서 적용된다)
- 팀·활동 멤버 수는 V4 데이터 채우기의 크기다(수천 행 이하면 1초 안)

---

## 1. Redis 설치·설정

채팅 서버 간 전달(Pub/Sub) 전용이다. 영속 데이터가 없다. **`deploy/README.md` 3-1의 스크립트를 그대로 쓴다.**

- 127.0.0.1에만 바인딩, 비밀번호, maxmemory 64MB, 저장 끔
- 비밀번호는 서버 안에서 만들어 `/etc/redis/odo.conf`와 `/etc/dance-api.env`에만 쓴다(명령줄에 비밀번호를 넣지 않는다)

**확인**

```bash
ssh odo 'REDISCLI_AUTH="$(sudo sed -n "s/^REDIS_PASSWORD=//p" /etc/dance-api.env)" redis-cli -h 127.0.0.1 ping'   # PONG
ssh odo 'redis-cli -h 127.0.0.1 ping'                       # NOAUTH (비밀번호 없이는 거부)
ssh odo 'ss -ltn | grep 6379'                                # 127.0.0.1 / [::1] 만
ssh odo 'sudo grep -cE "^REDIS_(HOST|PORT|PASSWORD)=" /etc/dance-api.env'   # 3
```

**되돌리기**(앱을 먼저 되돌린 뒤에만 — 새 jar는 Redis 없이 뜨지 않는다)

```bash
ssh odo 'sudo systemctl disable --now redis-server && sudo sed -i "/^REDIS_\(HOST\|PORT\|PASSWORD\)=/d" /etc/dance-api.env'
```

---

## 2. nginx 연결 상한

웹소켓 하나는 nginx 연결 둘(브라우저 쪽, Spring 쪽)을 쓴다. Ubuntu 기본값 `worker_connections 768`이면 웹소켓
약 380 × 워커 수에서 막힌다. 측정 환경은 16384 / 40000으로 풀었다(측정 전용). 운영은 서버 크기(2GB)에 맞춰
**4096 / 10000**으로 둔다. 워커 2개면 웹소켓 약 4,000개까지다. 연결 하나는 수 KB라 메모리 걱정은 작다.
열 수 있는 파일 수(`worker_rlimit_nofile`)는 연결 수의 두 배 이상이다.

```bash
ssh odo 'sudo bash -s' <<'REMOTE'
set -euo pipefail
cp -a /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak-chat
sed -i -E 's/^(\s*worker_connections)\s+[0-9]+;/\1 4096;/' /etc/nginx/nginx.conf
grep -q '^worker_rlimit_nofile' /etc/nginx/nginx.conf \
  || sed -i '/^events\s*{/i worker_rlimit_nofile 10000;' /etc/nginx/nginx.conf
nginx -t
REMOTE
```

사이트 설정(웹소켓 `location = /api/ws` 블록과 `map $http_upgrade`)도 함께 반영한다:

```bash
scp deploy/nginx/odostudio.site.conf odo:/tmp/
ssh odo 'sudo cp -a /etc/nginx/sites-available/odostudio.site.conf /etc/nginx/sites-available/odostudio.site.conf.bak-chat \
  && sudo cp /tmp/odostudio.site.conf /etc/nginx/sites-available/ && sudo nginx -t && sudo systemctl reload nginx'
```

**확인**

```bash
ssh odo 'sudo nginx -T 2>/dev/null | grep -E "worker_(connections|rlimit_nofile)|location = /api/ws"'
ssh odo 'grep "open files" /proc/$(pgrep -f "nginx: worker" | head -1)/limits'   # 10000
curl -sI https://odostudio.site | head -1                                         # 200
```

**되돌리기**

```bash
ssh odo 'sudo cp -a /etc/nginx/nginx.conf.bak-chat /etc/nginx/nginx.conf \
  && sudo cp -a /etc/nginx/sites-available/odostudio.site.conf.bak-chat /etc/nginx/sites-available/odostudio.site.conf \
  && sudo nginx -t && sudo systemctl reload nginx'
```

---

## 3. systemd 유닛 (`After=`에 redis-server.service)

배포는 유닛 파일을 바꾸지 않는다. 직접 바꾸고 **`daemon-reload`를 꼭 한다**(빠뜨리면 예전 유닛을 계속 쓴다).
재시작은 하지 않는다 — 4단계 배포가 새 jar로 재시작한다(지금 재시작하면 옛 jar가 다시 뜰 뿐이다).

```bash
scp deploy/systemd/dance-api.service odo:/tmp/
ssh odo 'sudo cp -a /etc/systemd/system/dance-api.service /etc/systemd/system/dance-api.service.bak-chat \
  && sudo cp /tmp/dance-api.service /etc/systemd/system/ && sudo systemctl daemon-reload'
```

**확인**

```bash
ssh odo 'systemctl show -p After dance-api | tr " " "\n" | grep -E "redis|postgres"'   # redis-server.service 보임
ssh odo 'systemctl is-active dance-api'                                                 # active (옛 jar 그대로)
```

**되돌리기**

```bash
ssh odo 'sudo cp -a /etc/systemd/system/dance-api.service.bak-chat /etc/systemd/system/dance-api.service && sudo systemctl daemon-reload'
```

---

## 4. 병합 → 배포

PR #4를 main에 병합한다(사람이 직접). deploy.yml이 다음 순서로 배포한다:

1. 현재 jar·화면을 `.bak`로 남긴다
2. 새 jar·화면을 올리고 `systemctl restart dance-api`
3. 기동 때 Flyway가 V4를 적용한다
4. 2분 안에 `/api/push/public-key`가 응답하지 않으면 직전 jar·화면으로 자동 롤백한다

GitHub Actions의 "배포" 실행이 초록인지 본다.

---

## 5. 배포 뒤 확인

```bash
ssh odo 'journalctl -u dance-api --since "10 min ago" --no-pager | grep -E "Redis 기동 확인|읽음 모으기\] 켬|Successfully applied|Started DanceAssessment"'
#   → "[읽음 모으기] 켬 주기=500ms 위상=0ms", Flyway V4 적용, 기동 완료
ssh odo "sudo -u postgres psql -d dance_db -Atc 'select version, success from flyway_schema_history order by installed_rank desc limit 1'"   # 4|t
ssh odo "sudo -u postgres psql -d dance_db -Atc 'select count(*) from chat_room where type = '\''TEAM'\''; select count(*) from chat_room_member'"   # 팀 수, 활동 멤버 수
# 웹소켓 경로가 Spring까지 가는지: 쿠키 없이 → 401이면 정상(200 HTML이면 nginx가 화면으로 보낸 것)
curl -s -o /dev/null -w '%{http_code}\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
     -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://odostudio.site/api/ws
# actuator·측정 전용 경로가 밖에서 열리지 않는지 → 401 또는 404
for p in /actuator /actuator/health /actuator/prometheus /actuator/loadtest; do
  printf '%s ' "$p"; curl -s -o /dev/null -w '%{http_code}\n' "https://odostudio.site$p"; done
ssh odo 'free -m | head -2; REDISCLI_AUTH="$(sudo sed -n "s/^REDIS_PASSWORD=//p" /etc/dance-api.env)" redis-cli -h 127.0.0.1 info memory | grep used_memory_human'
```

브라우저에서 확인한다:

- 로그인 → 팀 채팅방 → 메시지 보내기 → 다른 계정(다른 브라우저)에 바로 보이는지
- 읽음 표시가 1초 안에 바뀌는지. 묶기 500ms + 모으기 주기 500ms라 0.5~1초가 정상이다
- 개발자 도구 네트워크 탭: `/api/ws` 101, `POST /api/chat/rooms/{id}/read` 202

---

## 6. 되돌리기

| 상황 | 방법 |
|---|---|
| 배포 기동 실패 | deploy.yml이 직전 jar·화면으로 자동 롤백한다. 로그 `journalctl -u dance-api -n 100` |
| 새 기능만 끄고 싶다(재배포 없이) | `/etc/dance-api.env`에 `CHAT_READ_AGGREGATE=false`(보고마다 처리), `CHAT_WS_INBOUND_THREADS=0`(CPU × 2), `CHAT_READ_BULKHEAD=0`을 넣고 `sudo systemctl restart dance-api`. 화면의 묶기 500ms·백오프는 서버 설정과 상관없이 동작한다(확인 재전송은 202일 때만) |
| 앱을 직전 버전으로 | `sudo cp /opt/dance-api/app.jar.bak /opt/dance-api/app.jar && sudo rsync -a --delete /var/www/odostudio/dist.bak/ /var/www/odostudio/dist/ && sudo systemctl restart dance-api` |
| DB(V4) | **되돌리지 않는다.** V4는 새 테이블(chat_room, chat_room_member, message)만 만든다. 옛 jar는 그 테이블을 모른 채로 그대로 뜬다(`ddl-auto=validate`는 매핑한 엔티티만 본다). 지우면 채팅 데이터가 사라진다 |
| nginx·systemd·Redis | 각 단계의 "되돌리기". Redis는 앱을 먼저 되돌린 뒤에만 끈다 |

---

## 측정 환경과 운영의 설정 차이

| 항목 | 측정 값 | 운영 값 | 근거 |
|---|---|---|---|
| 프로필 | `prod,loadtest` | `prod` | loadtest는 운영 설정 위에 측정 전용 값만 덮는다 |
| 메시지 처리 스레드(`chat.ws.inbound-threads`) | 실행마다 지정(4 또는 8) | **8** | 3-2: 무릎 1,600~2,000 → 2,400~3,000. 아래 "스레드 8개 판단" |
| 서버 쪽 읽음 모으기(`chat.read.aggregate.enabled`) | 실행마다 지정 | **켬** | 5-1·5-2: 800 전달 26.6 → 10.5, 앱 CPU −31%, PG −57% |
| 서버 순번·수·위상 | 2대(순번 0·1, 위상 0·250) | 1대(순번 0, 수 1 → 위상 0) | 서버가 한 대다. 늘리면 `CHAT_SERVER_INDEX`·`CHAT_SERVER_COUNT`를 준다(위상 나누기 F2가 F1보다 전달 p95 6ms 좋음) |
| 읽음 벌크헤드 K | 실행마다(0·12·32) | **32** | 작업 4: 모으기를 켜면 L ≈ 0 → K = max(32, 10 × L) |
| 발행을 커넥션 반납 뒤로(E) | 비교 때만 켬 | 끔 | 효과가 보정 오차 안이라 채택하지 않음 |
| 화면 묶기 W(`VITE_CHAT_READ_THROTTLE_MS`) | 생성기 0 또는 500 | **500**(코드 기본값) | B: 보고 1,600 → 400건/초, 앱 CPU −45% |
| 재전송(`VITE_CHAT_READ_RETRY_MODE`) | 실행마다 | **backoff**(기본값) | 5-3: 장애 중 재전송 −61% |
| 확인 재전송(`VITE_CHAT_READ_CONFIRM_MS`) | 모으기 실행 2000 | **2000**(기본값) | 5-4: 버린 값 18개 모두 4.8초에 수렴 |
| actuator 노출 | 관리 포트 9091에 prometheus·health·loadtest | **없음**(`exclude=*`) | 운영은 지표를 로그 요약으로만 본다. `ProdProfileTest`가 401/404 확인 |
| Prometheus 레지스트리 | 켬 | 끔 | 같은 jar, 설정으로만 |
| 측정 전용 장애 주입(`/actuator/loadtest`, ReadFaults) | 빈 있음 | **빈 없음** | `@Profile("loadtest")`, 인증 예외도 loadtest일 때만 |
| 메시지 단계 분해(`chat.metrics.send-stages`) | 켬 | 끔 | 측정용 지표 |
| 모으기 느린 주기 기록(`chat.metrics.read-agg-slow`) | 켬 | 끔 | 측정용 지표·로그 |
| 스레드별 CPU(`chat.metrics.thread-cpu`), Tomcat mbean | 켬 | 끔 | 측정용 지표 |
| 요약 로그 간격 | 10초 | 60초 | |
| nginx 연결 상한 | 16384 / 40000 | **4096 / 10000** | 2단계 |
| Hikari 풀 | 10 | 10 | 바꾸지 않음(스레드 8 < 풀 10) |
| Secure 쿠키 | 끔(LAN http) | 켬 | |
| 저장소 | local | R2 | |
| 비밀값 | 측정 전용(`deploy/loadtest/.env`, 저장소 밖, 윈도우에만) | `/etc/dance-api.env` | 커밋 기록에 측정·운영 비밀값 없음(10/21 확인) |

### 스레드 8개가 운영(2 vCPU)에 과하지 않은가

- 메시지 1건 처리 약 4ms 중 대부분이 DB 커밋 대기(WAL 디스크 동기화)다
  - 측정: 커밋 69~77%, 인바운드 스레드 CPU는 1건당 약 0.37ms(초당 1,500건에 0.55코어)
  - 스레드 8개가 모두 바빠도 CPU는 약 0.7코어다. 나머지 시간은 기다린다
- 기본값(CPU × 2)이면 2 vCPU에서 4개다. 처리량 상한은 4 / 4ms ≈ 초당 1,000건이고, 8개면 약 2,000건이다(그룹 커밋으로 디스크 동기화를 나눠 씀)
- 운영 서버 한 대의 실제 부하는 그보다 훨씬 작을 것으로 본다. 스레드는 쉬는 동안 CPU를 쓰지 않는다. 메모리는 스레드당 스택 약 1MB다
- 상한 요인: Hikari 풀 10개 중 최대 8개를 메시지가 쓴다. 측정에서 스레드 8개 + 초당 3,000건일 때 획득 대기는 평균 0.15ms였다
- 운영 디스크의 동기화 속도는 확인하지 않았다(측정 장비는 WSL2 ext4, 1.9ms)
- 문제가 보이면 `CHAT_WS_INBOUND_THREADS=4`로 재배포 없이 줄인다

## 운영 DB 변경 (Flyway)

이번 병합으로 적용되는 마이그레이션은 **`V4__chat.sql` 하나**다. V1~V3는 main에 이미 있다.

| 문장 | 하는 일 | 잠금 | 기존 행 |
|---|---|---|---|
| `CREATE TABLE chat_room` + PK·UNIQUE(team_id)·UNIQUE(dm 쌍)·CHECK | 새 테이블 | 새 테이블에만 | 없음 |
| `ALTER TABLE chat_room ADD FOREIGN KEY … REFERENCES team / users` | 외래 키 | 참조되는 `team`·`users`에 SHARE ROW EXCLUSIVE(쓰기만 막음, 읽기는 됨) — **V4 트랜잭션이 끝날 때까지** | 새 테이블이 비어 있어 검증이 즉시 끝난다 |
| `CREATE TABLE chat_room_member` + 인덱스 2개 + FK(room, users) | 새 테이블 | 위와 같음(`users`) | 없음 |
| `CREATE TABLE message` + UNIQUE·부분 UNIQUE + FK(room, users) | 새 테이블 | 위와 같음 | 없음 |
| `INSERT INTO chat_room SELECT … FROM team` | 팀마다 팀 채팅방 하나 | `team` 읽기 | 팀 수만큼 새 행. 기존 테이블은 바꾸지 않는다 |
| `INSERT INTO chat_room_member SELECT … FROM team_member … WHERE status = 'ACTIVE'` | 활동 팀원을 방 멤버로(읽음 위치 0) | `team_member` 읽기 | 활동 멤버 수만큼 새 행 |
| `DO $$ … RAISE NOTICE` | 채운 수를 로그로 | 읽기 | 없음 |

- **기존 테이블의 열·제약은 바꾸지 않는다.** 새 테이블 셋과 채우기뿐이다
- 잠금 시간: Flyway가 V4를 한 트랜잭션으로 돌린다. `team`·`users` 쓰기는 V4가 끝날 때까지 기다린다
  - 배포 재시작 중(옛 앱이 내려간 뒤 새 앱 기동 전)에 돌아서 들어오는 요청이 없다
  - 데이터가 수천 행 수준이면 1초 안에 끝난다(0단계에서 행 수 확인)
- 실패하면 트랜잭션이 통째로 되돌아가고 새 앱은 뜨지 않는다 → 배포가 직전 jar로 자동 롤백한다. 다음 배포에서 다시 적용된다
- 되돌리기: V4는 되돌리지 않는다(6절). 옛 jar는 새 테이블이 있어도 그대로 뜬다
