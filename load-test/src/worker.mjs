import { monitorEventLoopDelay, performance } from 'node:perf_hooks';
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
// read = 화면에 붙은 순간 → 다른 사람의 READ에서 반영을 본 순간(2.4의 정의), readSend = 보낸 순간부터(기준선 정의)
const KINDS = ['delivery', 'ack', 'read', 'readSend', 'readHttp', 'lag'];
const REPORT_TTL_NS = 60_000_000_000n;

let config;
let workerIndex;
let sessions = [];
let readyCount = 0;
let measuring = false;
let histograms = {};
let counters = new Map();
// 받아야 할 프레임 수를 조정자가 계산하도록 방별로 센다(측정 구간만): 보낸 메시지·읽음 보고 수
let perRoom = new Map();
let schedule = { rooms: [] };
let sendTimer = null;
// 방:사용자 → [{ seq, t0 }] 번호 오름차순. visible = contiguousTo가 그 번호에 닿은 순간, sent = 그 번호를 보낸 순간
const visible = new Map();
const sentReports = new Map();
const loopDelay = monitorEventLoopDelay({ resolution: 1 });
loopDelay.enable();

const resetMeasures = () => {
  histograms = Object.fromEntries(KINDS.map((k) => [k, createHistogram()]));
  counters = new Map();
  perRoom = new Map();
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
  roomCount(roomId, kind) {
    if (!measuring) return;
    let c = perRoom.get(roomId);
    if (!c) perRoom.set(roomId, (c = { sent: 0, reports: 0 }));
    c[kind] += 1;
  },
  readVisible(roomId, userId, seq, t) {
    push(visible, `${roomId}:${userId}`, seq, t);
  },
  readSent(roomId, userId, seq, t0) {
    push(sentReports, `${roomId}:${userId}`, seq, t0);
  },
  /** 받은 READ에서 그 사용자의 위치가 prev → pos로 올랐다: 그 사이 번호가 화면에 붙은 시각(read)·보낸 시각(readSend)부터 잰다. */
  readReflected(roomId, userId, prev, pos, at, readyAt) {
    const key = `${roomId}:${userId}`;
    const list = visible.get(key);
    if (!list) {
      // 보고한 세션이 다른 작업 프로세스에 있다(1인 2팀) — 재지 않고 센다(단계 1 C7)
      ctx.count('readUnmeasured');
      return;
    }
    recordBetween('read', list, prev, pos, at, readyAt);
    recordBetween('readSend', sentReports.get(key) ?? [], prev, pos, at, readyAt);
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

function push(map, key, seq, t) {
  let list = map.get(key);
  if (!list) map.set(key, (list = []));
  list.push({ seq, t0: t });
}

/** 번호가 커지는 순서로 쌓인 목록에서 prev보다 큰 첫 항목을 이분 탐색으로 찾고 pos까지 잰다 */
function recordBetween(kind, list, prev, pos, at, readyAt) {
  let lo = 0;
  let hi = list.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (list[mid].seq <= prev) lo = mid + 1;
    else hi = mid;
  }
  for (let i = lo; i < list.length && list[i].seq <= pos; i++) {
    if (list[i].t0 >= readyAt) ctx.record(kind, at - list[i].t0);
  }
}

/** 보고 있는 방의 읽음 보고 상태: 서버가 아직 받아들이지 않은 화면 위치(behind), 예약 없이 멈춘 것(stuck) */
function readStatus() {
  let watched = 0;
  let behind = 0;
  let stuck = 0;
  for (const s of sessions) {
    const st = s.readStatus();
    if (!st) continue;
    watched += 1;
    if (st.behind) behind += 1;
    if (st.stuck) stuck += 1;
  }
  return { watched, behind, stuck };
}

/** 이 프로세스의 세션이 구독한 방별 세션 수(세션은 자기 모든 방을 구독한다) */
function subscriptionsByRoom() {
  const m = new Map();
  for (const s of sessions) for (const r of s.rooms ?? []) m.set(r, (m.get(r) ?? 0) + 1);
  return m;
}

function pruneReports() {
  const limit = process.hrtime.bigint() - REPORT_TTL_NS;
  for (const map of [visible, sentReports]) {
    for (const [key, list] of map) {
      const kept = list.filter((r) => r.t0 >= limit);
      if (kept.length === 0) map.delete(key);
      else map.set(key, kept);
    }
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
      if (result === 'sent') ctx.roomCount(room.roomId, 'sent');
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
  const elu0 = performance.eventLoopUtilization();
  const t0 = process.hrtime.bigint();
  measuring = true;
  await new Promise((r) => setTimeout(r, durationMs));
  measuring = false;
  // 이벤트 루프 사용률: 루프가 일한 시간 / 전체. 1에 가까우면 받은 데이터를 제때 꺼내지 못한다(루프 지연으로는 안 보인다)
  const elu = performance.eventLoopUtilization(elu0).utilization;
  const elapsedUs = Number((process.hrtime.bigint() - t0) / 1000n);
  const cpu = process.cpuUsage(cpu0);
  return {
    workerIndex,
    sessions: sessions.length,
    ready: sessions.filter((s) => s.ready).length,
    histograms: Object.fromEntries(KINDS.map((k) => [k, encode(histograms[k])])),
    counters: Object.fromEntries(counters),
    cpuPercentOfCore: Math.round(((cpu.user + cpu.system) / elapsedUs) * 1000) / 10,
    eventLoopUtilization: Math.round(elu * 1000) / 1000,
    perRoom: Object.fromEntries(perRoom),
    subscriptions: Object.fromEntries(subscriptionsByRoom()),
    loopDelayMs: { p50: loopDelay.percentile(50) / 1e6, p99: loopDelay.percentile(99) / 1e6, max: loopDelay.max / 1e6 },
    readStatus: readStatus(),
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
    } else if (msg.cmd === 'readStatus') {
      process.send({ id: msg.id, ok: true, ...readStatus() });
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
