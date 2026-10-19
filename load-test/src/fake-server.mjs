import http from 'node:http';
import { WebSocketServer } from 'ws';
import { buildTopology } from './topology.mjs';
import { createHistogram, recordNs, summarize } from './hist.mjs';

/**
 * 측정기를 먼저 측정하기 위한 가짜 서버(Task-chat-read 9.3, 단계 1 E1). 사용:
 *   node src/fake-server.mjs --port 18080 --delay fixed:50 --topology '{"rooms":1,"members":20}'
 *
 * 실제 서버와 같은 목적지·응답 모양을 흉내 내되, 처리 대신 **정해 둔 지연**만 넣는다:
 * - SEND /app/rooms/{id}/messages → d 뒤에 보낸 세션에 ACK, 방 구독자 전원에게 MESSAGE
 * - POST /api/chat/rooms/{id}/read → 바로 204, d 뒤에 방 구독자 전원에게 READ
 * - SEND /app/sync → 바로 SYNC / GET /rooms·messages·read-state → 바로 응답
 * 지연 d: `fixed:50`(ms) / `uniform:20:80` / `fixed:0`.
 *
 * 넣은 지연과 타이머가 실제로 늦은 정도를 함께 기록한다(GET /fake/stats). 생성기가 잰 값 − 이 값 = 생성기 오차.
 *
 * 따로 띄운 가짜 서버(측정 환경의 fake-server 컨테이너)는 POST /fake/configure {delay, topology}로 경우마다
 * 지연·배치를 바꾼다(붙어 있던 연결은 끊고 방 상태를 처음으로).
 */
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]]);
  return acc;
}, []));
const port = Number(args.port ?? 18080);
let delaySpec = (args.delay ?? 'fixed:0').split(':');
let topo = buildTopology(JSON.parse(args.topology ?? '{"rooms":1,"members":20}'));

const sampleDelayMs = () => {
  if (delaySpec[0] === 'uniform') {
    const lo = Number(delaySpec[1]);
    const hi = Number(delaySpec[2]);
    return lo + Math.random() * (hi - lo);
  }
  return Number(delaySpec[1] ?? 0);
};

// 방 상태
let rooms = new Map();   // roomId → { lastSeq, positions: Map(userId → seq), version, subscribers: Set(conn) }
const room = (id) => {
  let r = rooms.get(id);
  if (!r) {
    r = { lastSeq: 0, positions: new Map(), version: 0, subscribers: new Set() };
    rooms.set(id, r);
  }
  return r;
};
const resetRooms = () => {
  rooms = new Map();
  for (const t of topo.teams) t.members.forEach((u) => room(t.roomId).positions.set(topo.userId(u), 0));
  for (const dm of topo.dms) [dm.a, dm.b].forEach((u) => room(dm.roomId).positions.set(topo.userId(u), 0));
};
resetRooms();

// 기록: 넣은 지연, 타이머가 예정보다 늦은 정도(넣은 지연 + 늦음 = 서버 쪽에서 실제로 걸린 시간)
let stats;
let pendingTimers = 0;
const resetStats = () => {
  stats = { injected: createHistogram(), timerLate: createHistogram(), serverSpan: createHistogram(), sent: 0, readBroadcasts: 0, readSkipped: 0, readPosts: 0, framesOut: 0, pending: 0 };
};
resetStats();

// 다시 설정할 때마다 늘린다. 앞 경우에서 걸어 둔 타이머(지연 d)가 다시 설정한 뒤에 울리면 새 방 상태를 덮어쓴다
// (앞 경우의 큰 읽은 위치가 들어가 새 경우의 보고가 전부 "이미 읽음"으로 건너뛰어졌다) → 세대가 다르면 버린다
let generation = 0;
/**
 * 받은 시각부터 d 뒤에 fn(응답·방송). 기록:
 * - timerLate: 타이머가 예정보다 늦게 울린 정도
 * - serverSpan: 프레임마다 send 콜백(압축 + 소켓에 씀) 시각 − 예정 시각. 몰림(20명이 한꺼번에 보고 → 방송 20 × 20)과
 *   압축을 가짜 서버가 처리하는 시간까지 들어간다. 생성기 오차 = 잰 값 − 넣은 지연 − 이 값
 */
const later = (fn) => {
  const gen = generation;
  const d = sampleDelayMs();
  const receivedAt = process.hrtime.bigint();
  const due = receivedAt + BigInt(Math.round(d * 1e6));
  recordNs(stats.injected, BigInt(Math.round(d * 1e6)));
  pendingTimers += 1;
  setTimeout(() => {
    pendingTimers -= 1;
    if (gen !== generation) return;
    recordNs(stats.timerLate, process.hrtime.bigint() - due);
    batch = { due, gen };
    try {
      fn();
    } finally {
      batch = null;
    }
  }, d);
};

const userOf = (cookie) => {
  const token = /access_token=([^;]+)/.exec(cookie ?? '')?.[1];
  if (!token) return null;
  return Number(JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()).sub);
};

const ranges = (positions) => {
  const sorted = [...positions.values()].sort((a, b) => a - b);
  const out = [];
  let i = 0;
  while (i < sorted.length) {
    const p = sorted[i];
    out.push({ toSeq: p, unread: i });
    while (i < sorted.length && sorted[i] === p) i++;
  }
  out.push({ toSeq: null, unread: sorted.length });
  return out;
};
const snapshot = (roomId, r) => ({
  version: ++r.version,
  positions: [...r.positions].map(([userId, lastReadSeq]) => ({ userId, lastReadSeq })),
  ranges: ranges(r.positions),
  roomId,
});

// ===== STOMP =====

const frame = (command, headers, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`;

// later()가 fn을 부르는 동안의 send마다: permessage-deflate는 send 뒤에 비동기로 압축하므로 send 콜백(압축 + 소켓에 씀)이
// 끝난 시각 − 예정 시각을 프레임 하나의 가짜 서버 처리 시간으로 기록한다(생성기가 프레임마다 재는 것과 같은 단위)
let batch = null;

function deliver(conn, destination, payload) {
  const subId = conn.subs.get(destination);
  if (subId == null || conn.ws.readyState !== 1) return;
  stats.framesOut += 1;
  const b = batch;
  conn.ws.send(frame('MESSAGE', {
    destination, 'subscription': subId, 'message-id': String(++conn.messageIds), 'content-type': 'application/json',
  }, JSON.stringify(payload)), () => {
    if (b && b.gen === generation) recordNs(stats.serverSpan, process.hrtime.bigint() - b.due);
  });
}

function broadcast(roomId, payload) {
  const dest = `/topic/rooms.${roomId}`;
  for (const conn of room(roomId).subscribers) deliver(conn, dest, payload);
}

function onFrame(conn, raw) {
  const text = raw.replace(/^[\r\n]+/, '');
  if (!text) return;
  const end = text.indexOf('\n\n');
  const lines = text.slice(0, end < 0 ? text.length : end).split('\n');
  const body = end < 0 ? '' : text.slice(end + 2);
  const command = lines[0].trim();
  const headers = {};
  for (const l of lines.slice(1)) {
    const c = l.indexOf(':');
    if (c > 0 && !(l.slice(0, c) in headers)) headers[l.slice(0, c)] = l.slice(c + 1);
  }
  if (command === 'CONNECT' || command === 'STOMP') {
    conn.ws.send(frame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }));
  } else if (command === 'SUBSCRIBE') {
    conn.subs.set(headers.destination, headers.id);
    const m = /^\/topic\/rooms\.(\d+)$/.exec(headers.destination);
    if (m) room(Number(m[1])).subscribers.add(conn);
  } else if (command === 'SEND' && headers.destination === '/app/sync') {
    deliver(conn, '/user/queue/acks', { type: 'SYNC' });
  } else if (command === 'SEND') {
    const m = /^\/app\/rooms\/(\d+)\/messages$/.exec(headers.destination);
    if (!m) return;
    const roomId = Number(m[1]);
    const msg = JSON.parse(body);
    later(() => {
      const r = room(roomId);
      const seq = ++r.lastSeq;
      const createdAt = new Date().toISOString();
      stats.sent += 1;
      deliver(conn, '/user/queue/acks', { type: 'ACK', clientMessageId: msg.clientMessageId, roomId, seq, createdAt, duplicate: false });
      broadcast(roomId, {
        roomId, seq, messageId: seq, senderId: conn.userId, senderNickname: 'lt', type: 'TEXT',
        content: msg.content, createdAt, clientMessageId: msg.clientMessageId, targetNickname: null,
      });
    });
  } else if (command === 'DISCONNECT') {
    conn.ws.close();
  }
}

// ===== HTTP =====

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const send = (status, payload) => {
    res.writeHead(status, payload == null ? {} : { 'Content-Type': 'application/json' });
    res.end(payload == null ? undefined : JSON.stringify(payload));
  };
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    if (url.pathname === '/fake/stats') {
      return send(200, { injected: summarize(stats.injected), timerLate: summarize(stats.timerLate), serverSpan: summarize(stats.serverSpan), sent: stats.sent, readPosts: stats.readPosts, readRejected: stats.readRejected ?? 0, readBroadcasts: stats.readBroadcasts, readSkipped: stats.readSkipped, pendingTimers, framesOut: stats.framesOut });
    }
    if (url.pathname === '/fake/reset') {
      resetStats();
      return send(204);
    }
    if (url.pathname === '/fake/configure' && req.method === 'POST') {
      const c = JSON.parse(body || '{}');
      generation += 1;
      for (const ws of wss.clients) ws.terminate();
      delaySpec = String(c.delay ?? 'fixed:0').split(':');
      topo = buildTopology(c.topology ?? { rooms: 1, members: 20 });
      resetRooms();
      resetStats();
      console.log(`[가짜 서버] 다시 설정: 지연=${delaySpec.join(':')} 방=${topo.rooms} 세션=${topo.userCount}`);
      return send(204);
    }
    const userId = userOf(req.headers.cookie);
    if (userId == null) return send(401, { code: 'UNAUTHORIZED' });
    if (url.pathname === '/api/chat/rooms') {
      const u = userId - topo.userId(0);
      return send(200, (topo.userRooms[u] ?? []).map((roomId) => ({ roomId, type: 'TEAM', displayName: 'lt', lastSeq: room(roomId).lastSeq, lastMessage: null, myReadSeq: 0, unread: 0, empty: false })));
    }
    let m = /^\/api\/chat\/rooms\/(\d+)\/messages$/.exec(url.pathname);
    if (m) return send(200, { roomId: Number(m[1]), lastSeq: room(Number(m[1])).lastSeq, messages: [] });
    m = /^\/api\/chat\/rooms\/(\d+)\/read-state$/.exec(url.pathname);
    if (m) {
      const r = room(Number(m[1]));
      return send(200, { ...snapshot(Number(m[1]), r), version: r.version });
    }
    m = /^\/api\/chat\/rooms\/(\d+)\/read$/.exec(url.pathname);
    if (m && req.method === 'POST') {
      const roomId = Number(m[1]);
      const { seq } = JSON.parse(body || '{}');
      stats.readPosts += 1;
      // 실제 서버와 같은 검사(READ_SEQ_AHEAD): 앞 경우에서 늦게 도착한 보고(큰 seq)가 새 방 상태를 덮지 않게 한다
      if (!(seq > 0) || seq > room(roomId).lastSeq) {
        stats.readRejected = (stats.readRejected ?? 0) + 1;
        return send(400, { code: 'READ_SEQ_AHEAD' });
      }
      send(204);
      later(() => {
        const r = room(roomId);
        if ((r.positions.get(userId) ?? 0) >= seq) {
          stats.readSkipped += 1;
          return;
        }
        r.positions.set(userId, seq);
        stats.readBroadcasts += 1;
        broadcast(roomId, { type: 'READ', ...snapshot(roomId, r) });
      });
      return undefined;
    }
    return send(404, { code: 'NOT_FOUND' });
  });
});

// 실제 서버(Tomcat)와 같게 permessage-deflate를 협상한다(--deflate off로 끌 수 있음). Tomcat은 크기와 상관없이 모든 메시지를
// 압축하므로 threshold 0. 2-4 첫 보정은 압축 없이 해서 생성기의 압축 해제 비용을 놓쳤다(보정 조건 불일치)
const deflate = args.deflate !== 'off';
const wss = new WebSocketServer({ noServer: true, perMessageDeflate: deflate ? { threshold: 0 } : false });
server.on('upgrade', (req, socket, head) => {
  if (!req.url.startsWith('/api/ws')) return socket.destroy();
  wss.handleUpgrade(req, socket, head, (ws) => {
    const conn = { ws, userId: userOf(req.headers.cookie), subs: new Map(), messageIds: 0, buffer: '' };
    ws.on('message', (data) => {
      conn.buffer += data.toString();
      let nul;
      while ((nul = conn.buffer.indexOf('\0')) >= 0) {
        const raw = conn.buffer.slice(0, nul);
        conn.buffer = conn.buffer.slice(nul + 1);
        onFrame(conn, raw);
      }
    });
    ws.on('close', () => rooms.forEach((r) => r.subscribers.delete(conn)));
  });
});

server.listen(port, () => console.log(`[가짜 서버] :${port} 압축=${deflate ? 'permessage-deflate' : '없음'} 지연=${delaySpec.join(':')} 방=${topo.rooms} 세션=${topo.userCount}`));
