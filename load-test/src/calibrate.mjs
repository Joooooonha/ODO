import { fork } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from './run.mjs';

/**
 * 측정기를 먼저 측정한다(Task-chat-read 9.3, 단계 1 E). 사용: `node src/calibrate.mjs configs/calibration-mac.json`
 *
 * 경우마다 가짜 서버(지연 d를 넣음)를 띄우고 생성기를 돌려, 생성기가 잰 값과 서버 쪽 실제 시간
 * (넣은 지연 + 가짜 서버 타이머가 늦은 정도)을 비교한다.
 *   오차(백분위수별) ≈ 생성기 측정값 − 넣은 지연 − 가짜 서버 타이머 늦음
 * 이 오차에는 같은 맥 안의 루프백 왕복과 생성기 자신의 처리·기록 지연이 들어 있다. 기준: p99 오차 ≤ 5ms.
 * 가짜 서버도 같은 맥에서 돌므로(E4 "같은 맥에서 한 번") 서로 CPU를 나눠 쓴다 — 그래서 서버 쪽 늦음을 따로 빼고 본다.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 경우마다 가짜 서버를 준비한다. LOADTEST_FAKE_URL이 있으면 따로 떠 있는 가짜 서버(측정 환경의 fake-server 컨테이너,
 * 서버 몫 CPU 안)를 /fake/configure로 다시 설정하고, 없으면 같은 기계에 자식 프로세스로 띄운다(맥 보정).
 */
async function openFake(port, delay, topology) {
  const external = process.env.LOADTEST_FAKE_URL;
  if (external) {
    await fetch(`${external}/fake/configure`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ delay, topology }),
    });
    await sleep(500);
    return { url: external, close: async () => {} };
  }
  const child = fork(path.join(here, 'fake-server.mjs'), ['--port', String(port), '--delay', delay, '--topology', JSON.stringify(topology)],
    { stdio: ['ignore', 'pipe', 'inherit', 'ipc'] });
  await new Promise((resolve) => child.stdout.on('data', (d) => d.toString().includes('[가짜 서버]') && resolve()));
  return {
    url: `http://127.0.0.1:${port}`,
    close: async () => {
      child.kill();
      await sleep(500);
    },
  };
}

const getJson = async (url, method = 'GET') => {
  const res = await fetch(url, { method });
  return res.status === 204 ? null : res.json();
};

async function main() {
  const plan = JSON.parse(readFileSync(process.argv[2], 'utf8'));
  process.env.LOADTEST_JWT_SECRET ??= 'fake-server-does-not-verify-signatures-0123456789';
  const outDir = path.resolve(here, '..', 'results', 'raw', `calibration-${new Date().toISOString().replace(/[:.]/g, '-')}`);
  mkdirSync(outDir, { recursive: true });
  const rows = [];
  for (const [i, c] of plan.cases.entries()) {
    const topology = { ...plan.defaults.topology, ...c.topology };
    const fake = await openFake(18100 + i, c.delay, topology);
    const base = fake.url;
    const config = {
      ...plan.defaults, ...c, topology,
      target: { httpUrl: base, wsUrl: `${base.replace(/^http/, 'ws')}/api/ws`, origin: 'http://localhost:5173' },
      runId: `${path.basename(outDir)}/case-${i + 1}`,
      stages: [{ r: c.r }],
    };
    console.log(`\n=== 경우 ${i + 1}: ${c.name} (지연 ${c.delay}, 세션 ${topology.rooms * topology.members}, 프로세스 ${config.workers}, r=${c.r}) ===`);
    const { stages } = await run(config, { onMeasureStart: () => getJson(`${base}/fake/reset`, 'POST') });
    const server = await getJson(`${base}/fake/stats`);
    await fake.close();
    const s = stages[0];
    const err = (m, p) => (m[p] == null ? null : Math.round((m[p] - server.injected[p] - server.timerLate[p]) * 100) / 100);
    const row = {
      case: i + 1, name: c.name, delay: c.delay, sessions: topology.rooms * topology.members, workers: config.workers,
      sessionsPerWorker: Math.ceil((topology.rooms * topology.members) / config.workers), r: c.r, valid: s.valid,
      delivery: s.delivery, ack: s.ack, read: s.read, readHttp: s.readHttp,
      server,
      error: {
        delivery: { p50: err(s.delivery, 'p50'), p95: err(s.delivery, 'p95'), p99: err(s.delivery, 'p99') },
        ack: { p50: err(s.ack, 'p50'), p95: err(s.ack, 'p95'), p99: err(s.ack, 'p99') },
        read: { p50: err(s.read, 'p50'), p95: err(s.read, 'p95'), p99: err(s.read, 'p99') },
      },
      generator: s.generator, counters: s.counters,
    };
    // 판정(준하 결정 A): 직접 잰 오차 p99 ≤ errorLimitMs. 루프 지연 기준(loopDelayLimitMs)은 설정에 있을 때만 함께 본다
    const limit = plan.errorLimitMs ?? 5;
    const within = (e) => e.p99 == null || e.p99 <= limit;
    row.pass = row.valid !== false && row.error.delivery.p99 != null && within(row.error.delivery) && within(row.error.ack) && within(row.error.read);
    rows.push(row);
    console.log(`→ 오차(ms) 전달 p50 ${row.error.delivery.p50} / p95 ${row.error.delivery.p95} / p99 ${row.error.delivery.p99}, 확정 p99 ${row.error.ack.p99}, 읽음 p99 ${row.error.read.p99}, 서버 타이머 늦음 p99 ${server.timerLate.p99}, 판정 ${row.pass ? '통과' : '넘음'}`);
    writeFileSync(path.join(outDir, 'calibration.json'), JSON.stringify(rows, null, 2));
  }
  console.log(`\n결과: ${outDir}/calibration.json`);
  process.exit(0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
