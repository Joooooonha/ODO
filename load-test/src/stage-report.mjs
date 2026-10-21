import { readFileSync } from 'node:fs';

/**
 * 측정 결과 단계별 정리(2-4). 생성기 결과 + 보정 오차 + 서버 지표(Prometheus) + 컨테이너 throttle을 한 표로.
 *   node src/stage-report.mjs --stages <stages.json> --calib <calibration.json>[,…] --cgroup <cgroup.jsonl> [--prom http://127.0.0.1:9090]
 *   (맥에서: ssh -L 9090:127.0.0.1:9090 junha@<윈도우> 로 Prometheus를 연 뒤)
 *
 * 판정 규칙(준하 결정, 2-4):
 * - 성공 기준: 전달·확정 p95 ≤ 100ms, 읽음 반영 p95 ≤ 1초
 * - 측정 유효: 같은 설정(비율·r)의 보정 오차 p99 ≤ 기준의 5%(전달·확정 5ms, 읽음 50ms)
 * - 한도를 넘어도 |측정 p95 − 기준| > 보정 오차 p99 이면 통과/실패 판정은 유효(guard band). 그 안이면 "판정 보류"
 * - 개선 전후 비교는 차이가 그 단계 보정 오차보다 클 때만 개선 효과로 본다(이 스크립트는 값만 낸다)
 * - app·PG 밖의 컨테이너가 throttle되면(그 단계 주기의 1% 이상) 그 단계는 무효 표시
 * - 포화(Task-chat-read-improve 4절, 준하 승인 — 결과를 본 뒤 바꾼 규칙이라 원래 규칙의 판정도 함께 낸다):
 *   서버 쪽 신호가 작다 = 앱 throttle < 5%(두 대 모두) 이고 인바운드 대기 p99 < 100ms 이고 보내기 대기열 최대 < 100
 *   생성기 포화 = 이벤트 루프 사용률 ≥ 0.9, 또는 (받은 방 프레임 < 99% 이면서 서버 쪽 신호가 작음)
 *   서버 포화 = 받은 방 프레임 < 99% 이면서 서버 쪽 신호가 큼. 생성기 포화(루프 사용률)와 서버 포화가 함께면 판정 불가
 *   원래 규칙(2-4): 루프 사용률 ≥ 0.9 또는 받은 방 프레임 < 99% → 생성기 포화
 */
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]]);
  return acc;
}, []));
const PROM = args.prom ?? 'http://127.0.0.1:9090';
const CRITERIA = { delivery: 100, ack: 100, read: 1000 };
const LIMIT = { delivery: 5, ack: 5, read: 50 };
const SERVER_SIDE = new Set(['app1', 'app2', 'postgres']);

const stagesDoc = JSON.parse(readFileSync(args.stages, 'utf8'));
const env = JSON.parse(readFileSync(args.stages.replace(/stages\.json$/, 'env.json'), 'utf8'));
const { watchersPerRoom: watchers, members } = env.config.topology;
const calib = (args.calib ?? '').split(',').filter(Boolean).flatMap((f) => JSON.parse(readFileSync(f, 'utf8')));
// 같은 배치(세션 수 · 시청 비율) · 같은 r · 고정 50ms 보정 경우. 없으면 "보정 없음"(포화 판정만)
// 읽음 보고 묶기(readThrottleMs)도 같아야 같은 설정이다(없는 옛 보정 = 0)
const throttle = env.config.readThrottleMs ?? 0;
const calibFor = (r) => calib.filter((c) => c.delay === 'fixed:50' && c.r === r && c.sessions === env.sessions
  && (c.readThrottleMs ?? 0) === throttle
  && (c.name.includes(`${watchers}:${members - watchers}`))).at(-1) ?? null;
const SMALL = { appThrottle: 0.05, inboundWaitP99Ms: 100, outboundQueuedMax: 100 };

/** 포화 판정: 원래 규칙과 수정 규칙(머리말) */
function saturation(s, server, cg) {
  const elu = s.generator?.worstEventLoopUtilization ?? 0;
  const ratio = s.frames?.ratio;
  const short = ratio != null && ratio < 0.99;
  const appThrottle = Math.max(cg.app1?.ratio ?? 0, cg.app2?.ratio ?? 0);
  const inboundWait = Math.max(0, ...Object.values(server.inboundWaitP99Ms).filter(Number.isFinite));
  const outboundQueued = Math.max(0, ...Object.values(server.outboundQueuedMax).filter(Number.isFinite));
  const serverSmall = appThrottle < SMALL.appThrottle && inboundWait < SMALL.inboundWaitP99Ms && outboundQueued < SMALL.outboundQueuedMax;
  const generator = elu >= 0.9 || (short && serverSmall);
  const serverSat = short && !serverSmall;
  const verdict = generator && serverSat ? '판정 불가' : generator ? '생성기 포화' : serverSat ? '서버 포화' : '포화 아님';
  return {
    original: elu >= 0.9 || short ? '생성기 포화' : '포화 아님',
    revised: verdict,
    signals: { elu, framesRatio: ratio ?? null, appThrottle: round(appThrottle, 3), inboundWaitP99Ms: inboundWait, outboundQueuedMax: outboundQueued, serverSmall },
  };
}

const cgRows = new Map();
for (const line of readFileSync(args.cgroup, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const row = JSON.parse(line);
  row.ms = Date.parse(row.t);
  const name = row.name.replace(/^odo-loadtest-/, '').replace(/-\d+$/, '').replace(/-run-[0-9a-f]+$/, '');
  if (!cgRows.has(name)) cgRows.set(name, []);
  cgRows.get(name).push(row);
}
function cgroupWindow(from, to) {
  const out = {};
  for (const [name, rows] of cgRows) {
    const a = rows.filter((r) => r.ms <= from).at(-1);
    const b = rows.find((r) => r.ms >= to);
    if (!a || !b || (b.usage_usec < a.usage_usec && b.usage_usec !== -2147483648)) continue;
    const periods = b.nr_periods - a.nr_periods;
    // 고치기 전 recorder(%d)는 2^31에서 막힌 값을 남겼다 — 그 구간은 CPU를 모른다(null), throttle은 그대로 쓴다
    const clamped = [a.usage_usec, b.usage_usec].some((u) => Math.abs(u) === 2147483648 || u === 2147483647);
    out[name] = {
      cpus: clamped ? null : Math.round(((b.usage_usec - a.usage_usec) / 1e6 / ((b.ms - a.ms) / 1000)) * 100) / 100,
      throttled: b.nr_throttled - a.nr_throttled,
      periods,
      ratio: periods ? (b.nr_throttled - a.nr_throttled) / periods : 0,
    };
  }
  return out;
}

async function prom(expr, at) {
  const res = await fetch(`${PROM}/api/v1/query?query=${encodeURIComponent(expr)}&time=${at / 1000}`);
  const body = await res.json();
  return body.data.result.map((r) => ({ labels: r.metric, value: Number(r.value[1]) }));
}
const one = async (expr, at, key = 'server') => Object.fromEntries((await prom(expr, at)).map((r) => [r.labels[key] ?? 'all', round(r.value)]));
const round = (x, d = 2) => (Number.isFinite(x) ? Math.round(x * 10 ** d) / 10 ** d : x);

function judge(kind, measured, cal) {
  const p95 = measured?.p95;
  if (p95 == null) return { verdict: '없음' };
  const pass = p95 <= CRITERIA[kind];
  const err = cal?.error?.[kind]?.p99;
  if (err == null) return { verdict: pass ? '통과' : '실패', validity: '보정 없음', err: null };
  const valid = err <= LIMIT[kind];
  if (valid) return { verdict: pass ? '통과' : '실패', validity: '유효', err };
  const guarded = Math.abs(p95 - CRITERIA[kind]) > Math.abs(err);
  return { verdict: guarded ? (pass ? '통과' : '실패') : '판정 보류', validity: guarded ? '오차 초과·판정 유효(guard band)' : '오차 초과', err };
}

const rows = [];
for (const s of stagesDoc) {
  const from = Date.parse(s.startedAt);
  const to = Date.parse(s.endedAt);
  const w = `${Math.round((to - from) / 1000)}s`;
  const cal = calibFor(s.r);
  const server = {
    cpu: await one(`avg_over_time(process_cpu_usage[${w}])`, to),
    heapMaxMb: await one(`max_over_time(sum by (server) (jvm_memory_used_bytes{area="heap"})[${w}:5s]) / 1e6`, to),
    hikariActiveMax: await one(`max_over_time(hikaricp_connections_active[${w}])`, to),
    hikariPendingMax: await one(`max_over_time(hikaricp_connections_pending[${w}])`, to),
    hikariAcquireMaxMs: await one(`max_over_time(hikaricp_connections_acquire_seconds_max[${w}]) * 1000`, to),
    sendP99Ms: await one(`max_over_time(chat_send_duration_seconds{quantile="0.99"}[${w}]) * 1000`, to),
    hikariAcquireAvgMs: await one(`sum by (server) (increase(hikaricp_connections_acquire_seconds_sum[${w}])) / sum by (server) (increase(hikaricp_connections_acquire_seconds_count[${w}])) * 1000`, to),
    hikariUsageAvgMs: await one(`sum by (server) (increase(hikaricp_connections_usage_seconds_sum[${w}])) / sum by (server) (increase(hikaricp_connections_usage_seconds_count[${w}])) * 1000`, to),
    // 작업 3: 메시지 1건 처리 단계별 평균(SendStages). 서버:단계 → ms
    sendStageAvgMs: Object.fromEntries((await prom(`sum by (server, stage) (increase(chat_send_stage_seconds_sum[${w}])) / sum by (server, stage) (increase(chat_send_stage_seconds_count[${w}])) * 1000`, to))
      .map((r) => [`${r.labels.server}:${r.labels.stage}`, round(r.value, 3)])),
    // 10/21: 메시지 커밋 단계 최대(Timer 최대는 최근 2분 창) — 모으기 주기의 커밋 지연과 같은 순간인지 보려고
    sendCommitMaxMs: await one(`max_over_time(chat_send_stage_seconds_max{stage="commit"}[${w}]) * 1000`, to),
    sendAvgMs: await one(`sum by (server) (increase(chat_send_duration_seconds_sum[${w}])) / sum by (server) (increase(chat_send_duration_seconds_count[${w}])) * 1000`, to),
    readAvgMs: await one(`sum by (server) (increase(chat_read_duration_seconds_sum{outcome="advanced"}[${w}])) / sum by (server) (increase(chat_read_duration_seconds_count{outcome="advanced"}[${w}])) * 1000`, to),
    appCores: await one(`avg_over_time(process_cpu_usage[${w}]) * 2`, to),
    readP99Ms: await one(`max_over_time(chat_read_duration_seconds{quantile="0.99", outcome="advanced"}[${w}]) * 1000`, to),
    inboundWaitP99Ms: await one(`max_over_time(chat_session_queue_wait_seconds{quantile="0.99"}[${w}]) * 1000`, to),
    redisWaitP99Ms: await one(`max_over_time(chat_redis_dispatch_wait_seconds{quantile="0.99"}[${w}]) * 1000`, to),
    redisQueuedMax: await one(`max_over_time(chat_redis_dispatch_queued[${w}])`, to),
    redisDurP99Ms: Object.fromEntries((await prom(`max by (server, type) (max_over_time(chat_redis_dispatch_duration_seconds{quantile="0.99"}[${w}])) * 1000`, to))
      .map((r) => [`${r.labels.server}:${r.labels.type}`, round(r.value)])),
    // 개선(Task-chat-read-improve): 스레드 풀별 CPU(코어), Tomcat 사용 중 스레드, 읽음 거절·동시 처리, K 계산 근거
    threadCores: Object.fromEntries((await prom(`sum by (server, pool) (rate(chat_threads_cpu_seconds_total[${w}]))`, to))
      .map((r) => [`${r.labels.server}:${r.labels.pool}`, round(r.value, 3)])),
    tomcatBusyMax: await one(`max_over_time(tomcat_threads_busy_threads[${w}])`, to),
    tomcatBusyAvg: await one(`avg_over_time(tomcat_threads_busy_threads[${w}])`, to),
    readPerSec: Object.fromEntries((await prom(`sum by (server, outcome) (increase(chat_read_requests_total[${w}])) / ${(to - from) / 1000}`, to))
      .map((r) => [`${r.labels.server}:${r.labels.outcome}`, round(r.value, 1)])),
    readInflightMax: await one(`max_over_time(chat_read_inflight[${w}])`, to),
    readInflightAvg: await one(`avg_over_time(chat_read_inflight[${w}])`, to),
    readP50Ms: await one(`avg_over_time(chat_read_duration_seconds{quantile="0.5", outcome="advanced"}[${w}]) * 1000`, to),
    readP95Ms: await one(`max_over_time(chat_read_duration_seconds{quantile="0.95", outcome="advanced"}[${w}]) * 1000`, to),
    bulkheadLimit: await one(`max_over_time(chat_read_bulkhead_limit[${w}])`, to),
    outboundQueuedMax: await one(`max_over_time(executor_queued_tasks{name="clientOutboundChannelExecutor"}[${w}])`, to),
    inboundQueuedMax: await one(`max_over_time(executor_queued_tasks{name="clientInboundChannelExecutor"}[${w}])`, to),
    hikariActiveAvg: await one(`avg_over_time(hikaricp_connections_active[${w}])`, to),
    hikariPendingAvg: await one(`avg_over_time(hikaricp_connections_pending[${w}])`, to),
    framesPerSec: await one(`sum by (type) (increase(chat_outbound_frames_total[${w}])) / ${(to - from) / 1000}`, to, 'type'),
    publishPerSec: await one(`sum by (type) (increase(chat_redis_publish_total[${w}])) / ${(to - from) / 1000}`, to, 'type'),
  };
  // 작업 4: 서버 쪽 읽음 모으기(ReadAggregator). 꺼져 있으면 값이 비어 있다. 처리 시간 t·커넥션 보유·잠금 대기는 ms
  const sec = (to - from) / 1000;
  const avgMs = (name, sel = '') => one(`sum by (server) (increase(${name}_sum${sel}[${w}])) / sum by (server) (increase(${name}_count${sel}[${w}])) * 1000`, to);
  const perSec = (expr) => one(`sum by (server) (increase(${expr}[${w}])) / ${sec}`, to);
  const agg = {
    cycleAvgMs: await avgMs('chat_read_agg_cycle_seconds'),
    cycleMaxMs: await one(`max_over_time(chat_read_agg_cycle_seconds_max[${w}]) * 1000`, to),
    cycleP99Ms: await one(`max_over_time(chat_read_agg_cycle_seconds{quantile="0.99"}[${w}]) * 1000`, to),
    cyclesPerSec: await perSec('chat_read_agg_cycle_seconds_count'),
    entriesAvg: await one(`sum by (server) (increase(chat_read_agg_entries_sum[${w}])) / sum by (server) (increase(chat_read_agg_entries_count[${w}]))`, to),
    roomsAvg: await one(`sum by (server) (increase(chat_read_agg_rooms_sum[${w}])) / sum by (server) (increase(chat_read_agg_rooms_count[${w}]))`, to),
    roomsMax: await one(`max_over_time(chat_read_agg_rooms_max[${w}])`, to),
    writeHoldAvgMs: await avgMs('chat_read_agg_connection_seconds', '{phase="write"}'),
    snapshotHoldAvgMs: await avgMs('chat_read_agg_connection_seconds', '{phase="snapshot"}'),
    // 읽음 처리가 커넥션을 쥔 시간 합(초당 ms) — 5-1 비교용
    holdMsPerSec: await one(`sum by (server) (increase(chat_read_agg_connection_seconds_sum[${w}])) * 1000 / ${sec}`, to),
    lockAvgMs: await avgMs('chat_read_agg_lock_seconds'),
    lockMaxMs: await one(`max_over_time(chat_read_agg_lock_seconds_max[${w}]) * 1000`, to),
    commitsPerSec: await perSec('chat_read_agg_commits_total'),
    rowsPerSec: Object.fromEntries((await prom(`sum by (server, result) (increase(chat_read_agg_rows_total[${w}])) / ${sec}`, to))
      .map((r) => [`${r.labels.server}:${r.labels.result}`, round(r.value, 1)])),
    snapshotsPerSec: await perSec('chat_read_agg_snapshots_total'),
    publishesPerSec: await perSec('chat_read_agg_publishes_total'),
    failures: Object.fromEntries((await prom(`sum by (server, cause) (increase(chat_read_agg_failures_total[${w}]))`, to))
      .map((r) => [`${r.labels.server}:${r.labels.cause}`, round(r.value, 0)])),
    overruns: await one(`sum by (server) (increase(chat_read_agg_overruns_total[${w}]))`, to),
    dropped: await one(`sum by (server) (increase(chat_read_agg_dropped_total[${w}]))`, to),
    bufferMax: await one(`max_over_time(chat_read_agg_buffer[${w}])`, to),
    phaseMs: await one(`max_over_time(chat_read_agg_phase[${w}])`, to),
    // 10/21: t ≥ 100ms인 주기 수, 그 주기 번호(기동 뒤 몇 번째) 최대, 단계별 평균
    slowCycles: await one(`sum by (server) (increase(chat_read_agg_slow_index_count[${w}]))`, to),
    slowIndexMax: await one(`max_over_time(chat_read_agg_slow_index_max[${w}])`, to),
    slowStageAvgMs: Object.fromEntries((await prom(`sum by (server, stage) (increase(chat_read_agg_slow_stage_seconds_sum[${w}])) / sum by (server, stage) (increase(chat_read_agg_slow_stage_seconds_count[${w}])) * 1000`, to))
      .map((r) => [`${r.labels.server}:${r.labels.stage}`, round(r.value, 1)])),
  };
  const db = {
    lockWaitMax: (await one(`max_over_time((sum(pg_obs_sessions{wait_type="Lock"}) or vector(0))[${w}:1s])`, to)).all,
    idleInTxMax: (await one(`max_over_time((sum(pg_obs_sessions{state=~"idle in transaction.*"}) or vector(0))[${w}:1s])`, to)).all,
    lockByTarget: await one(`max by (target_table) (max_over_time(pg_obs_lock_waiting[${w}]))`, to, 'target_table'),
    maxLockWaitSec: (await one(`max_over_time(pg_obs_max_lock_wait_seconds[${w}])`, to)).all,
  };
  const cg = cgroupWindow(from, to);
  const throttledOthers = Object.entries(cg).filter(([n, v]) => !SERVER_SIDE.has(n) && v.ratio >= 0.01).map(([n, v]) => `${n} ${round(v.ratio * 100, 1)}%`);
  rows.push({
    r: s.r, startedAt: s.startedAt, endedAt: s.endedAt,
    delivery: s.delivery, ack: s.ack, read: s.read, readSend: s.readSend ?? null, readHttp: s.readHttp, readStatus: s.readStatus ?? null,
    deliveryNear: s.deliveryNear ?? null, deliveryFar: s.deliveryFar ?? null, faults: s.faults ?? null,
    judge: { delivery: judge('delivery', s.delivery, cal), ack: judge('ack', s.ack, cal), read: judge('read', s.read, cal) },
    calibCase: cal?.name ?? null,
    counters: s.counters, generator: { cgroup: s.cgroup, worstLoopP99: s.generator.worstLoopDelayP99Ms, loopReview: s.loopReview },
    server, agg, db, cgroup: cg,
    saturated: s.saturated ?? null, saturatedReasons: s.saturatedReasons ?? [], frames: s.frames ?? null,
    saturation: saturation(s, server, cg),
    unexpectedLock: (db.lockByTarget.other ?? 0) > 0,
    invalidThrottle: throttledOthers,
  });
}
process.stdout.write(JSON.stringify(rows, null, 2) + '\n');
