import { fork, execSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTopology, workerOfTeam } from './topology.mjs';
import { COOKIE_NAME, signToken } from './jwt.mjs';
import { merge, summarize } from './hist.mjs';

/**
 * 부하 생성기 조정자. 사용: `node src/run.mjs <설정.json>`
 *
 * 1. 배치 계산(시드와 같은 함수) → 세션을 방(주 팀) 단위로 작업 프로세스 K개에 나눈다
 * 2. 프로세스 간 시계 확인(IPC 왕복 중간점과의 차이) — 같은 단조 시계인지 실행마다 기록
 * 3. 연결 붙이기(초당 connectRatePerSec개) → 모두 붙을 때까지 기다림
 * 4. 단계(r)마다: 보내기 속도 설정 → 워밍업 → 측정(작업 프로세스가 구간 안의 값만 기록) → 합치기
 * 5. results/raw/<실행 id>/에 env.json(환경 기록)·stages.json(요약 + 압축 히스토그램)·summary.csv
 *
 * 생성기 포화 판정: 어느 작업 프로세스든 측정 구간의 이벤트 루프 지연 p99가 loopDelayLimitMs를 넘으면
 * 그 단계는 invalid(받은 시각 기록이 늦어져 지연이 부풀었을 수 있다).
 */
const here = path.dirname(fileURLToPath(import.meta.url));

export async function run(config, { log = console.log, onMeasureStart } = {}) {
  const secret = process.env[config.jwtSecretEnv ?? 'LOADTEST_JWT_SECRET'];
  if (!secret) throw new Error(`JWT 비밀키 환경 변수 ${config.jwtSecretEnv ?? 'LOADTEST_JWT_SECRET'}가 없다(측정 전용 값)`);
  // 대상 주소: 설정에 없으면 환경 변수에서(측정 환경 주소를 설정 파일에 자리 표시자로 두지 않는다)
  config.target ??= {
    httpUrl: process.env.LOADTEST_HTTP_URL,
    wsUrl: process.env.LOADTEST_WS_URL,
    origin: process.env.LOADTEST_ORIGIN,
  };
  for (const k of ['httpUrl', 'wsUrl', 'origin']) {
    if (!config.target[k]) throw new Error(`대상 주소 ${k}가 없다(설정 target 또는 LOADTEST_HTTP_URL·LOADTEST_WS_URL·LOADTEST_ORIGIN)`);
  }
  const topo = buildTopology(config.topology);
  const K = config.workers;

  // ===== 세션 배치 =====
  const perWorker = Array.from({ length: K }, () => []);
  const watchedOf = new Map();
  for (const team of topo.teams) for (const u of team.watchers) if (!watchedOf.has(u)) watchedOf.set(u, team.roomId);
  for (let u = 0; u < topo.userCount; u++) {
    const userId = topo.userId(u);
    perWorker[workerOfTeam(topo.primaryTeam(u), topo.rooms, K)].push({
      userId,
      rooms: topo.userRooms[u],
      watched: watchedOf.get(u) ?? null,
      cookie: `${COOKIE_NAME}=${signToken(secret, userId)}`,
    });
  }

  const workers = perWorker.map((sessions, i) => spawnWorker(i));
  await Promise.all(workers.map((w, i) => w.call({ cmd: 'init', workerIndex: i, config: publicConfig(config), sessions: perWorker[i] })));
  log(`[생성기] 세션 ${topo.userCount}개 · 작업 프로세스 ${K}개 (${perWorker.map((s) => s.length).join('/')})`);

  const clock = await clockCheck(workers);
  log(`[생성기] 프로세스 간 시계 차이(µs) p50=${clock.p50} 최대=${clock.maxAbs} (왕복 p50 ${clock.rttP50}µs)`);

  // ===== 연결 =====
  const connectStart = Date.now();
  const perWorkerRate = config.connectRatePerSec / K;
  await Promise.all(workers.map((w) => w.call({ cmd: 'connect', ratePerSec: perWorkerRate })));
  const live = await waitAllReady(workers, topo.userCount, config.connectTimeoutSec ?? 120, log);
  log(`[생성기] 연결 ${live}/${topo.userCount} (${Math.round((Date.now() - connectStart) / 1000)}초)`);

  // ===== 단계 =====
  const runId = config.runId ?? new Date().toISOString().replace(/[:.]/g, '-');
  const outDir = path.resolve(here, '..', 'results', 'raw', runId);
  mkdirSync(outDir, { recursive: true });
  writeFileSync(path.join(outDir, 'env.json'), JSON.stringify(environment(config, topo, clock), null, 2));

  // 예열(10/21, 판정에서 제외): 서버 쪽 모으기는 부하가 걸린 첫 주기 몇 개만 느리다(JIT·첫 실행 경로, t1-slow).
  // 서버 지표의 최대·분위수는 2분 창이라 예열을 측정 창 밖으로 밀어낸다. 기록·단계 결과 없이 r만 걸어 둔다
  if (config.prewarm) {
    const rooms = sendRates(topo, config, config.prewarm.r);
    await Promise.all(workers.map((w, i) => w.call({ cmd: 'rates', rooms: rooms.filter((r) => r.worker === i) })));
    log(`[예열 r=${config.prewarm.r}] ${config.prewarm.sec}초(판정 제외)`);
    await sleep(config.prewarm.sec * 1000);
  }

  const stages = [];
  for (const stage of config.stages) {
    const rooms = sendRates(topo, config, stage.r);
    await Promise.all(workers.map((w, i) => w.call({ cmd: 'rates', rooms: rooms.filter((r) => r.worker === i) })));
    log(`[단계 r=${stage.r}] 워밍업 ${config.warmupSec}초`);
    await sleep(config.warmupSec * 1000);
    log(`[단계 r=${stage.r}] 측정 ${config.measureSec}초`);
    await onMeasureStart?.(stage);
    const cpu0 = os.cpus();
    const cg0 = readCgroup();
    const startedAt = new Date().toISOString();
    const faults = scheduleFaults(config, stage.r, log);
    const nginx = pollNginx(config, config.measureSec);
    const results = await Promise.all(workers.map((w) => w.call({ cmd: 'measure', durationMs: config.measureSec * 1000 })));
    const machineCpu = machineCpuPercent(cpu0, os.cpus());
    const summary = {
      ...summarizeStage(stage.r, results.map((x) => x.result), machineCpu, config, cgroupDelta(cg0, readCgroup())),
      startedAt, endedAt: new Date().toISOString(),
      faults: await faults,
      nginx: await nginx,
    };
    stages.push(summary);
    log(formatStage(summary));
    writeFileSync(path.join(outDir, 'stages.json'), JSON.stringify(stages, null, 2));
  }
  await Promise.all(workers.map((w) => w.call({ cmd: 'rates', rooms: [] })));
  // 작업 5-4: 보내기를 멈추고 마지막 보고(창 끝 ≤ W)가 모두 서버 버퍼에 들어간 뒤, 모아 둔 값을 한 번 버리게 한다 →
  // 그 값들은 스냅샷 확인 재전송으로만 복구된다
  let dropAt = null;
  if (config.dropAtDrain) {
    await sleep((config.readThrottleMs ?? 0) + 100);
    dropAt = Date.now();
    await callFaultTargets(config, { action: 'read-drop-once' }, log);
  }
  // 보내기를 멈춘 뒤 화면 위치를 서버가 모두 받아들이는지(읽음 위치가 멈추지 않는지)
  const drain = await readDrain(workers, config.readDrainSec ?? 60, !!config.readConfirmMs);
  drain.dropAt = dropAt;
  log(`[생성기] 읽음 보고 정리: 보는 세션 ${drain.watched}, 서버보다 앞선 화면 ${drain.behind}, 스냅샷 미확인 ${drain.unconfirmed}, 멈춤 ${drain.stuck} (${drain.waitedSec}초${drain.convergedMs != null ? `, 수렴 ${drain.convergedMs}ms` : ''})`);
  writeFileSync(path.join(outDir, 'read-drain.json'), JSON.stringify(drain, null, 2));
  await Promise.all(workers.map((w) => w.call({ cmd: 'stop' }).catch(() => {})));
  writeFileSync(path.join(outDir, 'summary.csv'), toCsv(stages));
  log(`[생성기] 결과: ${outDir}`);
  return { outDir, stages, clock };
}

/** 보내기를 멈춘 뒤 모든 보는 세션의 화면 위치가 서버에 반영될 때까지(최대 timeoutSec) 기다린다. */
async function readDrain(workers, timeoutSec, needConfirm = false) {
  const t0 = Date.now();
  let last;
  let convergedMs = null;
  for (;;) {
    const st = await Promise.all(workers.map((w) => w.call({ cmd: 'readStatus' })));
    last = st.reduce((a, s) => ({ watched: a.watched + s.watched, behind: a.behind + s.behind, stuck: a.stuck + s.stuck, unconfirmed: a.unconfirmed + (s.unconfirmed ?? 0) }),
      { watched: 0, behind: 0, stuck: 0, unconfirmed: 0 });
    const done = last.behind === 0 && (!needConfirm || last.unconfirmed === 0);
    if (done) convergedMs = Date.now() - t0;
    if (done || Date.now() - t0 > timeoutSec * 1000) break;
    await sleep(needConfirm ? 250 : 1000);
  }
  return { ...last, waitedSec: Math.round((Date.now() - t0) / 1000), convergedMs };
}

/** 측정 전용 장애 주입 끝점(앱 관리 포트, compose 네트워크 안)을 두 서버에 부른다(작업 5-3·5-4) */
async function callFaultTargets(config, body, log) {
  const targets = config.faultTargets ?? ['http://app1:9091', 'http://app2:9091'];
  const at = Date.now();
  const res = await Promise.all(targets.map((t) => fetch(`${t}/actuator/loadtest`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  }).then((r) => r.status, (e) => String(e?.cause?.code ?? e))));
  log(`[생성기] 장애 주입 ${JSON.stringify(body)} → ${res.join(',')}`);
  return { ...body, at, status: res };
}

/** 이 단계에 걸린 장애 주입(config.faults: [{ r, atSec, action, seconds }])을 측정 시작 기준으로 예약한다 */
async function scheduleFaults(config, r, log) {
  const mine = (config.faults ?? []).filter((f) => f.r === r);
  return Promise.all(mine.map(async (f) => {
    await sleep(f.atSec * 1000);
    const { r: _r, atSec, ...body } = f;
    void _r;
    return { atSec, ...(await callFaultTargets(config, body, log)) };
  }));
}

/** nginx stub_status 누적 요청 수를 1초마다(config.nginxStatusUrl, 작업 5-3 "nginx 기준 초당 요청 수") */
async function pollNginx(config, sec) {
  if (!config.nginxStatusUrl) return null;
  const out = [];
  const t0 = Date.now();
  while (Date.now() - t0 < sec * 1000) {
    try {
      const text = await (await fetch(config.nginxStatusUrl)).text();
      const m = /\n\s*(\d+)\s+(\d+)\s+(\d+)/.exec(text);
      if (m) out.push([Date.now(), Number(m[3])]);
    } catch {
      out.push([Date.now(), null]);
    }
    await sleep(1000);
  }
  return out;
}

/** 방마다 보내기 속도. perRoom이면 활발한 방마다 r건/초, total이면 r을 활발한 방 수로 나눈다. DM 배경은 따로. */
function sendRates(topo, config, r) {
  const active = topo.teams.filter((t) => t.active);
  const perRoom = config.rateMode === 'total' ? r / active.length : r;
  const rooms = active.map((t) => {
    const senders = config.senders === 'all' || t.watchers.length === 0 ? t.members : t.watchers;
    return {
      roomId: t.roomId,
      ratePerSec: perRoom,
      senders: senders.map(topo.userId),
      worker: workerOfTeam(t.index, topo.rooms, config.workers),
    };
  });
  const dmRate = config.dmRatePerSec ?? 0;
  if (dmRate > 0 && topo.dms.length > 0) {
    for (const dm of topo.dms) {
      rooms.push({
        roomId: dm.roomId,
        ratePerSec: dmRate / topo.dms.length,
        senders: [topo.userId(dm.a), topo.userId(dm.b)],
        worker: workerOfTeam(dm.homeTeam, topo.rooms, config.workers),
      });
    }
  }
  return rooms;
}

function summarizeStage(r, results, machineCpu, config, cgroup) {
  const kinds = ['delivery', 'ack', 'read', 'readSend', 'readHttp', 'lag', 'deliveryNear', 'deliveryFar'];
  const merged = Object.fromEntries(kinds.map((k) => [k, merge(results.map((x) => x.histograms[k]))]));
  const counters = {};
  for (const x of results) for (const [k, v] of Object.entries(x.counters)) counters[k] = (counters[k] ?? 0) + v;
  const workerSelf = results.map((x) => ({
    worker: x.workerIndex, sessions: x.sessions, ready: x.ready, cpuPercentOfCore: x.cpuPercentOfCore,
    loopDelayP99Ms: round(x.loopDelayMs.p99), loopDelayMaxMs: round(x.loopDelayMs.max), heapMb: x.heapMb,
    eventLoopUtilization: x.eventLoopUtilization,
  }));
  // 생성기 포화: 이벤트 루프 사용률 ≥ 0.9 또는 받은 방 프레임 < 받아야 할 것의 99%.
  // 받아야 할 것 = 방마다 (보낸 메시지 + 읽음 보고) × 그 방을 구독한 세션 수(전 작업 프로세스 합). 프로세스 CPU는 압축 해제
  // 백그라운드 스레드 때문에 1코어를 넘을 수 있어 단독 기준으로 쓰지 않는다(기록만)
  const rooms = new Map();
  for (const x of results) {
    for (const [r, c] of Object.entries(x.perRoom ?? {})) {
      const e = rooms.get(r) ?? { sent: 0, reports: 0, subs: 0 };
      e.sent += c.sent;
      e.reports += c.reports;
      if (c.cycles) for (const k of c.cycles) (e.cycles ??= new Set()).add(k);
      rooms.set(r, e);
    }
    for (const [r, n] of Object.entries(x.subscriptions ?? {})) {
      const e = rooms.get(r) ?? { sent: 0, reports: 0, subs: 0 };
      e.subs += n;
      rooms.set(r, e);
    }
  }
  let expectedFrames = 0;
  // 서버 쪽 모으기(10/21 수정): READ 기대 수 = 보고가 있었던 주기 수(방마다, 작업 프로세스 합집합) × 구독 수.
  // 두 서버가 같은 주기에 같은 방을 따로 발행할 수 있어 하한이다(받은 비율이 1을 넘을 수 있다)
  for (const e of rooms.values()) expectedFrames += (e.sent + (config.flushWindows ? (e.cycles?.size ?? 0) : e.reports)) * e.subs;
  const receivedFrames = results.reduce((a, x) => a + (x.counters.messagesReceived ?? 0) + (x.counters.readFramesReceived ?? 0), 0);
  const receivedRatio = expectedFrames ? Math.round((receivedFrames / expectedFrames) * 10000) / 10000 : null;
  const worstElu = Math.max(...workerSelf.map((w) => w.eventLoopUtilization ?? 0));
  const saturatedReasons = [
    ...(worstElu >= 0.9 ? [`이벤트 루프 사용률 ${worstElu} ≥ 0.9`] : []),
    ...(receivedRatio != null && receivedRatio < 0.99 ? [`받은 방 프레임 ${Math.round(receivedRatio * 1000) / 10}% < 99%`] : []),
  ];
  const worstLoop = Math.max(...workerSelf.map((w) => w.loopDelayP99Ms));
  // 유효 판정: 같은 설정의 보정에서 직접 잰 오차로 한다 → 여기서는 루프 지연을 기록만 한다.
  // loopDelayLimitMs를 준 설정(맥 보정 때의 C4 기준)만 그 값으로 무효를 표시한다. 20ms(loopDelayReviewMs)를 넘으면 "검토"
  const limit = config.loopDelayLimitMs ?? Infinity;
  const review = config.loopDelayReviewMs ?? 20;
  const summary = {
    r,
    measureSec: config.measureSec,
    delivery: summarize(merged.delivery),
    ack: summarize(merged.ack),
    read: summarize(merged.read),
    readSend: summarize(merged.readSend),
    readHttp: summarize(merged.readHttp),
    deliveryNear: summarize(merged.deliveryNear),
    deliveryFar: summarize(merged.deliveryFar),
    timeline: mergeTimeline(results),
    readStatus: results.reduce((a, x) => ({ behind: a.behind + (x.readStatus?.behind ?? 0), stuck: a.stuck + (x.readStatus?.stuck ?? 0) }), { behind: 0, stuck: 0 }),
    scheduleLag: summarize(merged.lag),
    counters,
    generator: { machineCpuPercent: machineCpu, worstLoopDelayP99Ms: worstLoop, worstEventLoopUtilization: worstElu, workers: workerSelf },
    frames: { expected: expectedFrames, received: receivedFrames, ratio: receivedRatio },
    saturated: saturatedReasons.length > 0,
    saturatedReasons,
    valid: worstLoop <= limit,
    invalidReason: worstLoop <= limit ? null : `생성기 이벤트 루프 지연 p99 ${worstLoop}ms > ${limit}ms`,
    loopReview: workerSelf.some((w) => w.loopDelayMaxMs > review || w.loopDelayP99Ms > review),
    cgroup,
    histograms: Object.fromEntries(kinds.map((k) => [k, results.map((x) => x.histograms[k])])),
  };
  kinds.forEach((k) => merged[k].destroy?.());
  return summary;
}

/** 작업 프로세스들의 초 단위 시간선을 합친다: 초마다 카운터 합, 전달 지연 p50·p95·p99 */
function mergeTimeline(results) {
  if (!results.some((x) => x.timeline)) return null;
  const n = Math.max(...results.map((x) => x.timeline?.length ?? 0));
  const out = [];
  for (let i = 0; i < n; i++) {
    const counters = {};
    const hs = [];
    for (const x of results) {
      const t = x.timeline?.[i];
      if (!t) continue;
      for (const [k, v] of Object.entries(t.counters)) counters[k] = (counters[k] ?? 0) + v;
      if (t.delivery) hs.push(t.delivery);
    }
    const h = merge(hs);
    out.push({ sec: i, counters, delivery: summarize(h) });
    h.destroy?.();
  }
  return out;
}

function formatStage(s) {
  const f = (x) => (x.count ? `${x.count}건 p50 ${x.p50} / p95 ${x.p95} / p99 ${x.p99} / 최대 ${x.max}ms` : '0건');
  return [
    `[단계 r=${s.r}] ${s.saturated ? `생성기 포화 = 무효(${s.saturatedReasons.join(', ')}) ` : ''}${s.valid ? '' : `무효(${s.invalidReason}) `}${s.loopReview ? '검토(루프 지연 > 20ms) ' : ''}`,
    `  방 프레임 받음 ${s.frames.received}/${s.frames.expected}${s.frames.ratio != null ? ` (${Math.round(s.frames.ratio * 1000) / 10}%)` : ''}, 이벤트 루프 사용률 최대 ${s.generator.worstEventLoopUtilization}`,
    `  전달  ${f(s.delivery)}`,
    `  확정  ${f(s.ack)}`,
    `  읽음  ${f(s.read)}`,
    `  읽음(보낸 순간부터) ${f(s.readSend)}`,
    `  읽음 보고 상태(단계 끝): 서버보다 앞선 화면 ${s.readStatus.behind}, 멈춤 ${s.readStatus.stuck}`,
    `  읽음 보고 HTTP ${f(s.readHttp)}`,
    `  예약 늦음 ${f(s.scheduleLag)}`,
    ...(s.cgroup ? [`  생성기 cgroup ${s.cgroup.cpusUsed}/${s.cgroup.cpusLimit} CPU, throttle ${s.cgroup.nrThrottled}/${s.cgroup.nrPeriods}주기 ${s.cgroup.throttledMs}ms`] : []),
    `  생성기 CPU ${s.generator.machineCpuPercent}% (기계 전체), 프로세스별 ${s.generator.workers.map((w) => `${w.cpuPercentOfCore}%`).join('/')}, 루프 지연 p99 최대 ${s.generator.worstLoopDelayP99Ms}ms`,
    `  수 ${JSON.stringify(s.counters)}`,
  ].join('\n');
}

function toCsv(stages) {
  const head = ['r', 'valid', 'delivery_count', 'delivery_p50', 'delivery_p95', 'delivery_p99', 'delivery_max',
    'ack_count', 'ack_p50', 'ack_p95', 'ack_p99', 'ack_max', 'read_count', 'read_p50', 'read_p95', 'read_p99', 'read_max',
    'read_send_p95', 'read_stuck', 'lag_p99', 'gen_machine_cpu', 'gen_worst_loop_p99', 'loop_review', 'saturated', 'frames_ratio', 'worst_elu', 'gen_cgroup_cpus', 'gen_throttled_periods', 'gen_throttled_ms', 'sent', 'blocked', 'disconnects', 'read_reports', 'read_report_errors', 'read_posts', 'read_rejected'];
  const rows = stages.map((s) => [s.r, s.valid,
    ...['delivery', 'ack', 'read'].flatMap((k) => [s[k].count, s[k].p50, s[k].p95, s[k].p99, s[k].max]),
    s.readSend.p95, s.readStatus.stuck, s.scheduleLag.p99, s.generator.machineCpuPercent, s.generator.worstLoopDelayP99Ms, s.loopReview, s.saturated, s.frames.ratio ?? '', s.generator.worstEventLoopUtilization,
    s.cgroup?.cpusUsed ?? '', s.cgroup?.nrThrottled ?? '', s.cgroup?.throttledMs ?? '',
    s.counters.sent ?? 0, s.counters['send:blocked'] ?? 0, s.counters.disconnects ?? 0,
    s.counters.readReports ?? 0, s.counters.readReportErrors ?? 0, s.counters.readPosts ?? 0, s.counters.readRejected ?? 0]);
  return [head, ...rows].map((r) => r.join(',')).join('\n') + '\n';
}

// ===== 작업 프로세스 =====

function spawnWorker(index) {
  const child = fork(path.join(here, 'worker.mjs'), [], { stdio: ['ignore', 'inherit', 'inherit', 'ipc'] });
  let seq = 0;
  const waiting = new Map();
  child.on('message', (msg) => {
    const w = waiting.get(msg.id);
    if (!w) return;
    waiting.delete(msg.id);
    if (msg.ok) w.resolve(msg);
    else w.reject(new Error(`작업 프로세스 ${index}: ${msg.error}`));
  });
  child.on('exit', (code) => {
    for (const w of waiting.values()) w.reject(new Error(`작업 프로세스 ${index} 종료 code=${code}`));
    waiting.clear();
  });
  return {
    child,
    call(msg) {
      const id = ++seq;
      return new Promise((resolve, reject) => {
        waiting.set(id, { resolve, reject });
        child.send({ ...msg, id });
      });
    },
  };
}

/** 각 작업 프로세스와 20번 왕복: 상대 시각 − 왕복 중간점. 같은 시계면 0 근처(차이 ≤ 왕복/2). */
async function clockCheck(workers) {
  const offsets = [];
  const rtts = [];
  for (const w of workers) {
    for (let i = 0; i < 20; i++) {
      const t0 = process.hrtime.bigint();
      const { now } = await w.call({ cmd: 'clock' });
      const t1 = process.hrtime.bigint();
      rtts.push(Number(t1 - t0) / 1000);
      offsets.push(Number(BigInt(now) - (t0 + t1) / 2n) / 1000);
    }
  }
  const sorted = [...offsets].sort((a, b) => a - b);
  const rs = [...rtts].sort((a, b) => a - b);
  return {
    samples: offsets.length,
    p50: Math.round(sorted[Math.floor(sorted.length / 2)]),
    maxAbs: Math.round(Math.max(...offsets.map(Math.abs))),
    rttP50: Math.round(rs[Math.floor(rs.length / 2)]),
    withinHalfRtt: Math.round((offsets.filter((o, i) => Math.abs(o) <= rtts[i] / 2).length / offsets.length) * 100),
  };
}

async function waitAllReady(workers, total, timeoutSec, log) {
  const deadline = Date.now() + timeoutSec * 1000;
  let live = 0;
  while (Date.now() < deadline) {
    const statuses = await Promise.all(workers.map((w) => w.call({ cmd: 'status' })));
    live = statuses.reduce((a, s) => a + s.live, 0);
    if (live >= total) return live;
    await sleep(1000);
  }
  log(`[생성기] 경고: ${timeoutSec}초 안에 모두 붙지 못함 ${live}/${total}`);
  return live;
}

/**
 * 이 컨테이너(생성기)의 cgroup v2 CPU 기록. 컨테이너 밖(맥)에서는 null.
 * cpu.stat: usage_usec·nr_periods·nr_throttled·throttled_usec / cpu.max: "할당 주기" (상한)
 */
function readCgroup() {
  try {
    const stat = Object.fromEntries(readFileSync('/sys/fs/cgroup/cpu.stat', 'utf8').trim().split('\n')
      .map((l) => l.split(' ')).map(([k, v]) => [k, Number(v)]));
    const [quota, period] = readFileSync('/sys/fs/cgroup/cpu.max', 'utf8').trim().split(' ');
    return { at: process.hrtime.bigint(), stat, cpus: quota === 'max' ? null : Number(quota) / Number(period) };
  } catch {
    return null;
  }
}

function cgroupDelta(a, b) {
  if (!a || !b) return null;
  const d = (k) => (b.stat[k] ?? 0) - (a.stat[k] ?? 0);
  const elapsedUs = Number((b.at - a.at) / 1000n);
  return {
    cpusLimit: b.cpus,
    cpusUsed: Math.round((d('usage_usec') / elapsedUs) * 100) / 100,
    nrPeriods: d('nr_periods'),
    nrThrottled: d('nr_throttled'),
    throttledMs: Math.round(d('throttled_usec') / 1000),
  };
}

function machineCpuPercent(a, b) {
  let busy = 0;
  let all = 0;
  a.forEach((c, i) => {
    const d = (k) => b[i].times[k] - c.times[k];
    const total = d('user') + d('nice') + d('sys') + d('idle') + d('irq');
    busy += total - d('idle');
    all += total;
  });
  return all ? Math.round((busy / all) * 1000) / 10 : null;
}

function environment(config, topo, clock) {
  let commit = null;
  try {
    commit = execSync('git rev-parse HEAD', { cwd: here }).toString().trim();
  } catch {
    // git이 없는 곳에서 돌려도 된다
  }
  return {
    startedAt: new Date().toISOString(),
    commit,
    config: publicConfig(config),
    sessions: topo.userCount,
    generator: {
      host: os.hostname(), platform: `${os.platform()} ${os.release()}`, arch: os.arch(),
      cpus: os.cpus().length, cpuModel: os.cpus()[0]?.model, memGb: Math.round(os.totalmem() / 2 ** 30), node: process.version,
    },
    clock,
    // 서버 쪽 환경(CPU 제한·메모리·버전·네트워크 유선/무선)은 측정 환경 기록 파일에 따로 적는다(7절)
    note: config.note ?? null,
  };
}

/** 비밀키는 결과 파일·작업 프로세스로 넘기지 않는다(환경 변수 이름만). */
function publicConfig(config) {
  const { jwtSecret, ...rest } = config;
  void jwtSecret;
  return { ...rest, ...(config.target ?? {}) };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const round = (x) => Math.round(x * 100) / 100;

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const file = process.argv[2];
  if (!file) {
    console.error('사용: node src/run.mjs <설정.json>');
    process.exit(2);
  }
  const config = JSON.parse(readFileSync(file, 'utf8'));
  run(config).then(() => process.exit(0), (e) => {
    console.error(e);
    process.exit(1);
  });
}
