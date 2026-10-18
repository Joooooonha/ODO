import { fork, execSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildTopology, workerOfTeam } from './topology.mjs';
import { COOKIE_NAME, signToken } from './jwt.mjs';
import { merge, summarize } from './hist.mjs';

/**
 * 부하 생성기 조정자(Task-chat-read 6절). 사용: `node src/run.mjs <설정.json>`
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

  const stages = [];
  for (const stage of config.stages) {
    const rooms = sendRates(topo, config, stage.r);
    await Promise.all(workers.map((w, i) => w.call({ cmd: 'rates', rooms: rooms.filter((r) => r.worker === i) })));
    log(`[단계 r=${stage.r}] 워밍업 ${config.warmupSec}초`);
    await sleep(config.warmupSec * 1000);
    log(`[단계 r=${stage.r}] 측정 ${config.measureSec}초`);
    await onMeasureStart?.(stage);
    const cpu0 = os.cpus();
    const results = await Promise.all(workers.map((w) => w.call({ cmd: 'measure', durationMs: config.measureSec * 1000 })));
    const machineCpu = machineCpuPercent(cpu0, os.cpus());
    const summary = summarizeStage(stage.r, results.map((x) => x.result), machineCpu, config);
    stages.push(summary);
    log(formatStage(summary));
    writeFileSync(path.join(outDir, 'stages.json'), JSON.stringify(stages, null, 2));
  }
  await Promise.all(workers.map((w) => w.call({ cmd: 'rates', rooms: [] })));
  await Promise.all(workers.map((w) => w.call({ cmd: 'stop' }).catch(() => {})));
  writeFileSync(path.join(outDir, 'summary.csv'), toCsv(stages));
  log(`[생성기] 결과: ${outDir}`);
  return { outDir, stages, clock };
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

function summarizeStage(r, results, machineCpu, config) {
  const kinds = ['delivery', 'ack', 'read', 'lag'];
  const merged = Object.fromEntries(kinds.map((k) => [k, merge(results.map((x) => x.histograms[k]))]));
  const counters = {};
  for (const x of results) for (const [k, v] of Object.entries(x.counters)) counters[k] = (counters[k] ?? 0) + v;
  const workerSelf = results.map((x) => ({
    worker: x.workerIndex, sessions: x.sessions, ready: x.ready, cpuPercentOfCore: x.cpuPercentOfCore,
    loopDelayP99Ms: round(x.loopDelayMs.p99), loopDelayMaxMs: round(x.loopDelayMs.max), heapMb: x.heapMb,
  }));
  const worstLoop = Math.max(...workerSelf.map((w) => w.loopDelayP99Ms));
  const limit = config.loopDelayLimitMs ?? 5;
  const summary = {
    r,
    measureSec: config.measureSec,
    delivery: summarize(merged.delivery),
    ack: summarize(merged.ack),
    read: summarize(merged.read),
    scheduleLag: summarize(merged.lag),
    counters,
    generator: { machineCpuPercent: machineCpu, worstLoopDelayP99Ms: worstLoop, workers: workerSelf },
    valid: worstLoop <= limit,
    invalidReason: worstLoop <= limit ? null : `생성기 이벤트 루프 지연 p99 ${worstLoop}ms > ${limit}ms`,
    histograms: Object.fromEntries(kinds.map((k) => [k, results.map((x) => x.histograms[k])])),
  };
  kinds.forEach((k) => merged[k].destroy?.());
  return summary;
}

function formatStage(s) {
  const f = (x) => (x.count ? `${x.count}건 p50 ${x.p50} / p95 ${x.p95} / p99 ${x.p99} / 최대 ${x.max}ms` : '0건');
  return [
    `[단계 r=${s.r}] ${s.valid ? '유효' : `무효(${s.invalidReason})`}`,
    `  전달  ${f(s.delivery)}`,
    `  확정  ${f(s.ack)}`,
    `  읽음  ${f(s.read)}`,
    `  예약 늦음 ${f(s.scheduleLag)}`,
    `  생성기 CPU ${s.generator.machineCpuPercent}% (맥 전체), 프로세스별 ${s.generator.workers.map((w) => `${w.cpuPercentOfCore}%`).join('/')}, 루프 지연 p99 최대 ${s.generator.worstLoopDelayP99Ms}ms`,
    `  수 ${JSON.stringify(s.counters)}`,
  ].join('\n');
}

function toCsv(stages) {
  const head = ['r', 'valid', 'delivery_count', 'delivery_p50', 'delivery_p95', 'delivery_p99', 'delivery_max',
    'ack_count', 'ack_p50', 'ack_p95', 'ack_p99', 'ack_max', 'read_count', 'read_p50', 'read_p95', 'read_p99', 'read_max',
    'lag_p99', 'gen_machine_cpu', 'gen_worst_loop_p99', 'sent', 'blocked', 'disconnects', 'read_reports', 'read_report_errors'];
  const rows = stages.map((s) => [s.r, s.valid,
    ...['delivery', 'ack', 'read'].flatMap((k) => [s[k].count, s[k].p50, s[k].p95, s[k].p99, s[k].max]),
    s.scheduleLag.p99, s.generator.machineCpuPercent, s.generator.worstLoopDelayP99Ms,
    s.counters.sent ?? 0, s.counters['send:blocked'] ?? 0, s.counters.disconnects ?? 0,
    s.counters.readReports ?? 0, s.counters.readReportErrors ?? 0]);
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
