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
const calibFor = (r) => calib.filter((c) => c.delay === 'fixed:50' && c.r === r && c.sessions === env.sessions
  && (c.name.includes(`${watchers}:${members - watchers}`))).at(-1) ?? null;

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
    if (!a || !b || b.usage_usec < a.usage_usec) continue;
    const periods = b.nr_periods - a.nr_periods;
    out[name] = {
      cpus: Math.round(((b.usage_usec - a.usage_usec) / 1e6 / ((b.ms - a.ms) / 1000)) * 100) / 100,
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
    sendAvgMs: await one(`sum by (server) (increase(chat_send_duration_seconds_sum[${w}])) / sum by (server) (increase(chat_send_duration_seconds_count[${w}])) * 1000`, to),
    readAvgMs: await one(`sum by (server) (increase(chat_read_duration_seconds_sum{outcome="advanced"}[${w}])) / sum by (server) (increase(chat_read_duration_seconds_count{outcome="advanced"}[${w}])) * 1000`, to),
    appCores: await one(`avg_over_time(process_cpu_usage[${w}]) * 2`, to),
    readP99Ms: await one(`max_over_time(chat_read_duration_seconds{quantile="0.99", outcome="advanced"}[${w}]) * 1000`, to),
    inboundWaitP99Ms: await one(`max_over_time(chat_session_queue_wait_seconds{quantile="0.99"}[${w}]) * 1000`, to),
    redisWaitP99Ms: await one(`max_over_time(chat_redis_dispatch_wait_seconds{quantile="0.99"}[${w}]) * 1000`, to),
    redisQueuedMax: await one(`max_over_time(chat_redis_dispatch_queued[${w}])`, to),
    redisDurP99Ms: Object.fromEntries((await prom(`max by (server, type) (max_over_time(chat_redis_dispatch_duration_seconds{quantile="0.99"}[${w}])) * 1000`, to))
      .map((r) => [`${r.labels.server}:${r.labels.type}`, round(r.value)])),
    framesPerSec: await one(`sum by (type) (increase(chat_outbound_frames_total[${w}])) / ${(to - from) / 1000}`, to, 'type'),
    publishPerSec: await one(`sum by (type) (increase(chat_redis_publish_total[${w}])) / ${(to - from) / 1000}`, to, 'type'),
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
    delivery: s.delivery, ack: s.ack, read: s.read, readHttp: s.readHttp,
    judge: { delivery: judge('delivery', s.delivery, cal), ack: judge('ack', s.ack, cal), read: judge('read', s.read, cal) },
    calibCase: cal?.name ?? null,
    counters: s.counters, generator: { cgroup: s.cgroup, worstLoopP99: s.generator.worstLoopDelayP99Ms, loopReview: s.loopReview },
    server, db, cgroup: cg,
    saturated: s.saturated ?? null, saturatedReasons: s.saturatedReasons ?? [], frames: s.frames ?? null,
    unexpectedLock: (db.lockByTarget.other ?? 0) > 0,
    invalidThrottle: throttledOthers,
  });
}
process.stdout.write(JSON.stringify(rows, null, 2) + '\n');
