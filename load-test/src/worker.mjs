import { monitorEventLoopDelay } from 'node:perf_hooks';
import { Session } from './session.mjs';
import { createHistogram, encode, recordNs } from './hist.mjs';

/**
 * 작업 프로세스 하나. 조정자(run.mjs)가 fork하고 IPC로 명령한다. 방 단위로 배정받은 세션들을 붙이고,
 * 일정표대로(open-loop) 보내고, 받은 쪽 지연을 같은 시계로 잰다.
 *
 * 자기 검사(단계 1 C4): 측정 구간마다 이 프로세스의 CPU(process.cpuUsage), 이벤트 루프 지연 p99
 * (monitorEventLoopDelay), 예약 대비 실제 전송 늦음(lag 히스토그램)을 함께 보낸다. 루프 지연이 기준을
 * 넘으면 조정자가 그 단계를 "생성기 포화, 무효"로 표시한다.
 */
const KINDS = ['delivery', 'ack', 'read', 'lag'];
const REPORT_TTL_NS = 60_000_000_000n;

let config;
let workerIndex;
let sessions = [];
let readyCount = 0;
let measuring = false;
let histograms = {};
let counters = new Map();
let schedule = { rooms: [] };
let sendTimer = null;
const reports = new Map();   // 방:사용자 → [{ seq, t0 }] 보고 순(번호 오름차순)
const loopDelay = monitorEventLoopDelay({ resolution: 1 });
loopDelay.enable();

const resetMeasures = () => {
  histograms = Object.fromEntries(KINDS.map((k) => [k, createHistogram()]));
  counters = new Map();
};
resetMeasures();

const ctx = {
  get config() {
    return config;
  },
  padding: '',
  count(name, n = 1) {
    if (measuring) counters.set(name, (counters.get(name) ?? 0) + n);
  },
  record(kind, diffNs) {
    if (measuring) recordNs(histograms[kind], diffNs, () => ctx.count(`negative:${kind}`));
  },
  readReported(roomId, userId, seq, t0) {
    const key = `${roomId}:${userId}`;
    let list = reports.get(key);
    if (!list) reports.set(key, (list = []));
    list.push({ seq, t0 });
  },
  /** 받은 READ에서 그 사용자의 위치가 prev → pos로 올랐다: 그 사이 번호를 보고한 시각부터 잰다. */
  readReflected(roomId, userId, prev, pos, at, readyAt) {
    const list = reports.get(`${roomId}:${userId}`);
    if (!list) {
      // 보고한 세션이 다른 작업 프로세스에 있다(1인 2팀) — 재지 않고 센다(단계 1 C7)
      ctx.count('readUnmeasured');
      return;
    }
    // 보고는 번호가 커지는 순서로 쌓인다 → prev보다 큰 첫 보고를 이분 탐색으로 찾고 pos까지만 본다
    let lo = 0;
    let hi = list.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (list[mid].seq <= prev) lo = mid + 1;
      else hi = mid;
    }
    for (let i = lo; i < list.length && list[i].seq <= pos; i++) {
      if (list[i].t0 >= readyAt) ctx.record('read', at - list[i].t0);
    }
  },
  sessionReady() {
    readyCount += 1;
  },
  /**
   * 방 프레임 본문 읽기. 같은 방송은 이 프로세스의 그 방 세션 수만큼 똑같은 글자로 온다 — 한 번만 읽고 나눠 쓴다
   * (생성기 자신의 비용을 줄이려는 것. 결과 객체는 읽기만 한다). 최근 것만 기억한다.
   */
  parse(text) {
    let v = parsed.get(text);
    if (v === undefined) {
      v = JSON.parse(text);
      parsed.set(text, v);
      if (parsed.size > 2000) parsed.delete(parsed.keys().next().value);
    }
    return v;
  },
};
const parsed = new Map();

function pruneReports() {
  const limit = process.hrtime.bigint() - REPORT_TTL_NS;
  for (const [key, list] of reports) {
    const kept = list.filter((r) => r.t0 >= limit);
    if (kept.length === 0) reports.delete(key);
    else reports.set(key, kept);
  }
}
setInterval(pruneReports, 5_000).unref();

// ===== 보내기 일정표(open-loop) =====

/** schedule.rooms: [{ roomId, ratePerSec, senders: [userId] }] → 방마다 일정 간격, 시작 위치는 무작위. */
function startSchedule(rooms) {
  clearTimeout(sendTimer);
  const byUser = new Map(sessions.map((s) => [s.userId, s]));
  const now = process.hrtime.bigint();
  schedule = {
    rooms: rooms.filter((r) => r.ratePerSec > 0).map((r) => {
      const interval = BigInt(Math.round(1e9 / r.ratePerSec));
      return {
        roomId: r.roomId,
        interval,
        next: now + BigInt(Math.floor(Math.random() * Number(interval))),
        senders: r.senders.map((u) => byUser.get(u)).filter(Boolean),
        turn: 0,
      };
    }).filter((r) => r.senders.length > 0),
  };
  tick();
}

function tick() {
  const now = process.hrtime.bigint();
  let earliest = null;
  for (const room of schedule.rooms) {
    while (room.next <= now) {
      ctx.record('lag', now - room.next);
      const sender = room.senders[room.turn++ % room.senders.length];
      const result = sender.send(room.roomId);
      ctx.count(result === 'sent' ? 'sent' : `send:${result}`);
      room.next += room.interval;
    }
    if (earliest == null || room.next < earliest) earliest = room.next;
  }
  if (earliest == null) return;
  const waitMs = Number((earliest - process.hrtime.bigint()) / 1_000_000n);
  sendTimer = setTimeout(tick, Math.max(0, waitMs));
}

// ===== 명령 =====

async function connectAll(ratePerSec) {
  const gapMs = 1000 / ratePerSec;
  for (let i = 0; i < sessions.length; i++) {
    sessions[i].start();
    if (gapMs >= 1) await new Promise((r) => setTimeout(r, gapMs));
  }
}

async function measure(durationMs) {
  resetMeasures();
  loopDelay.reset();
  const cpu0 = process.cpuUsage();
  const t0 = process.hrtime.bigint();
  measuring = true;
  await new Promise((r) => setTimeout(r, durationMs));
  measuring = false;
  const elapsedUs = Number((process.hrtime.bigint() - t0) / 1000n);
  const cpu = process.cpuUsage(cpu0);
  return {
    workerIndex,
    sessions: sessions.length,
    ready: sessions.filter((s) => s.ready).length,
    histograms: Object.fromEntries(KINDS.map((k) => [k, encode(histograms[k])])),
    counters: Object.fromEntries(counters),
    cpuPercentOfCore: Math.round(((cpu.user + cpu.system) / elapsedUs) * 1000) / 10,
    loopDelayMs: { p50: loopDelay.percentile(50) / 1e6, p99: loopDelay.percentile(99) / 1e6, max: loopDelay.max / 1e6 },
    heapMb: Math.round(process.memoryUsage().heapUsed / 1e6),
  };
}

process.on('message', async (msg) => {
  try {
    if (msg.cmd === 'init') {
      config = msg.config;
      workerIndex = msg.workerIndex;
      ctx.padding = 'x'.repeat(Math.max(0, (config.contentLength ?? 40) - 30));
      sessions = msg.sessions.map((spec) => new Session(ctx, spec));
      process.send({ id: msg.id, ok: true, sessions: sessions.length });
    } else if (msg.cmd === 'clock') {
      process.send({ id: msg.id, ok: true, now: process.hrtime.bigint().toString() });
    } else if (msg.cmd === 'connect') {
      await connectAll(msg.ratePerSec);
      process.send({ id: msg.id, ok: true });
    } else if (msg.cmd === 'status') {
      process.send({ id: msg.id, ok: true, ready: readyCount, live: sessions.filter((s) => s.ready).length, total: sessions.length });
    } else if (msg.cmd === 'rates') {
      startSchedule(msg.rooms);
      process.send({ id: msg.id, ok: true });
    } else if (msg.cmd === 'measure') {
      const result = await measure(msg.durationMs);
      process.send({ id: msg.id, ok: true, result });
    } else if (msg.cmd === 'stop') {
      clearTimeout(sendTimer);
      sessions.forEach((s) => s.stop());
      process.send({ id: msg.id, ok: true });
      setTimeout(() => process.exit(0), 500);
    }
  } catch (e) {
    process.send({ id: msg.id, ok: false, error: String(e?.stack ?? e) });
  }
});
