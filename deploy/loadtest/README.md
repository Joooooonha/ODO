# 측정 환경 (윈도우 한 대, Docker Compose)

읽음 기준선 측정(Task-chat-read 7·8절, 2-4)용. 서버·생성기가 한 호스트에 있고, cgroup CPU 상한으로 나눈다.
같은 호스트 안의 간섭은 같은 조건의 보정으로 확인한다. 네트워크 홉이 없으므로 측정값은 서버 몫이다.
사용자 체감 = 측정값 + 인터넷 RTT(A2 지연 예산). 운영은 jar + systemd 그대로다.

## CPU·메모리 배분 (Docker Desktop 상한 16 CPU / 16.36GB, OS 몫 약 2)

| 컨테이너 | cpus | mem | 비고 |
|---|---|---|---|
| app1 / app2 | 2 / 2 | 2g / 2g | 운영 t4g.small, `-Xmx` 없음 |
| postgres | 2 | 1g | 운영 설정 4개(compose `command`) |
| redis | 1 | 128m | `maxmemory 64mb`, 영속화 끔 |
| nginx | 1 | 256m | |
| prometheus / grafana | 0.5 / 0.5 | 512m / 256m | |
| sql-exporter | 0.25 | 64m | DB 커넥션 1개 |
| recorder | 0.25 | 64m | docker stats + cgroup cpu.stat, 5초 |
| generator | `GENERATOR_CPUS`(기본 4.75) | 4g | 보정으로 정한다 |
| **합계** | **14.25** | | |

보정(`--profile calib`)에서는 서버 몫 8 CPU를 가짜 서버 1개(nginx 자리)와 stress 7개(app 2·2, PG 2, Redis 1)로 채운다.

## 쓰는 법 (윈도우 cmd, 저장소 루트)

```bat
:: SSH 세션에서는 Windows 자격 증명 저장소를 못 쓴다 → 익명 자격 도우미 설정으로 pull
set PATH=C:\Users\junha\odo-loadtest\bin;%PATH%
set DOCKER_CONFIG=C:\Users\junha\odo-loadtest\docker-config

powershell -NoProfile -ExecutionPolicy Bypass -File deploy\loadtest\scripts\make-env.ps1
docker compose -f deploy/loadtest/compose.yml --profile build run --rm builder
docker compose -f deploy/loadtest/compose.yml up -d
docker compose -f deploy/loadtest/compose.yml --profile tools run --rm generator npm ci
docker compose -f deploy/loadtest/compose.yml --profile tools run --rm generator node src/run.mjs configs/scenario1-1x19.json
```

- Grafana `127.0.0.1:3000`, Prometheus `127.0.0.1:9090`에만 열린다. 맥에서 보기: `ssh -L 3000:127.0.0.1:3000 junha@<윈도우>`
- 데이터 되돌리기: 앱을 멈춘 뒤 `PSQL="docker compose -f deploy/loadtest/compose.yml exec -T postgres psql -U dance" load-test/seed/db-template.sh reset`
- 끝나면 `docker compose -f deploy/loadtest/compose.yml --profile calib --profile tools down`(볼륨은 남는다. 지우려면 `-v`)
