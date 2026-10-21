# 부하 생성기 · 가짜 서버 · 시드 (Task-chat-read 6절, 9.3)

읽음 표시 **기준선**을 재기 위한 도구. 생성기는 서버와 **다른 컴퓨터**(맥북)에서 돌린다.

```
npm ci                 # @stomp/stompjs 7.3.0(화면과 같은 라이브러리) · ws 8.21.0 · hdr-histogram-js 3.0.1
npm run check          # 생성기 단위 테스트(히스토그램 정확도·배치·JWT)
```

## 구조

| 파일 | 하는 일 |
|---|---|
| `src/run.mjs` | 조정자. 배치 계산 → 세션을 방 단위로 작업 프로세스 K개에 나눔 → 시계 확인 → 연결(초당 N개) → 단계(r)마다 워밍업·측정 → `results/raw/<실행 id>/` |
| `src/worker.mjs` | 작업 프로세스. 세션들을 붙이고 **일정표대로(open-loop)** 보낸다. 지연을 같은 단조 시계(`process.hrtime.bigint()`)로 잰다. 자기 CPU·이벤트 루프 지연·예약 늦음을 함께 보고 |
| `src/session.mjs` | 브라우저 탭 하나의 기준선 동작: 구독 → SYNC → 조회, contiguousTo, 보고 있는 방은 **바로** 읽음 보고, 전송 창 50, 재연결. 받은 시각은 WebSocket raw 이벤트 순간 |
| `src/topology.mjs` | 누가 어느 팀·방·DM·프로세스인지. 시드와 생성기·가짜 서버가 같은 함수를 쓴다 |
| `src/fake-server.mjs` | 측정기를 먼저 재는 가짜 서버. 정해 둔 지연만 넣고 실제 서버와 같은 목적지·응답 모양 |
| `src/calibrate.mjs` | 가짜 서버를 경우마다 띄워 생성기 오차를 잰다(기준 p99 +5ms) |
| `seed/generate-sql.mjs` | 시드 SQL(사용자·팀·멤버·채팅방·채팅 멤버·DM). `generate_series`로 빠르게 |
| `seed/db-template.sh` | 출발점 저장(`save`) · 실행 전 되돌리기(`reset`) — 템플릿 DB |

## 재는 것 (같은 생성기 안에서)

- **전달**: 본문에 실은 보낸 시각 → 다른 세션이 받은 시각
- **확정**: 보낸 시각 → ACK 받은 시각
- **읽음 반영**: 세션 U가 seq s를 보고한 시각 → 다른 세션이 받은 READ에서 U의 위치가 s 이상이 된 시각
- 생성기 자신: 프로세스별 CPU, 이벤트 루프 지연 p99(기준 `loopDelayLimitMs`를 넘으면 그 단계 **무효**), 예약 대비 전송 늦음, 맥 전체 CPU

결과: `results/raw/<id>/env.json`(환경·설정·커밋·시계 확인) · `stages.json`(단계별 요약 + 프로세스별 압축 히스토그램) · `summary.csv`.
원본(`results/raw/`)은 커밋하지 않는다. 요약 표·Grafana 캡처·환경 기록만 `results/`에 문서로 커밋한다.

## 실행

대상 주소는 설정 파일에 두지 않고 환경 변수로 준다. JWT 키는 **측정 환경 전용 값**(운영 값 금지).

```
export LOADTEST_HTTP_URL=http://<측정 서버>:8088 LOADTEST_WS_URL=ws://<측정 서버>:8088/api/ws LOADTEST_ORIGIN=http://<측정 서버>:8088
export LOADTEST_JWT_SECRET=<측정 환경 .env의 JWT_SECRET>
ulimit -n 65536
node seed/generate-sql.mjs configs/scenario1-20x0.json > /tmp/seed.sql   # → 측정 DB에 넣고 db-template.sh save
node src/run.mjs configs/scenario1-20x0.json
```

설정: `topology`(rooms·members·teamsPerUser·dmPairsPerRoom·watchersPerRoom·activeRoomRatio), `workers`, `connectRatePerSec`,
`rateMode`(perRoom: 활발한 방마다 r건/초 · total: 전체 r건/초), `senders`(watchers: 보는 사람이 보냄 · all), `dmRatePerSec`,
`stages`, `warmupSec`, `measureSec`, `contentLength`, `loopDelayLimitMs`.

## 측정기 보정 (9.3)

```
node src/calibrate.mjs configs/calibration-mac.json        # 같은 맥에서(E4 첫 번째)
node src/calibrate.mjs configs/calibration-scenario1.json  # 시나리오 1(방 하나 = 프로세스 하나)의 한계
```
오차 ≈ 생성기가 잰 값 − 넣은 지연 − 가짜 서버 타이머 늦음. 가짜 서버도 같은 맥에서 돌기 때문에 서버 쪽 늦음을 빼고 본다.
