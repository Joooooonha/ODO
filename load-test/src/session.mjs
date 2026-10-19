import { randomUUID } from 'node:crypto';
import { Client } from '@stomp/stompjs';

/**
 * 세션 하나 = 브라우저 탭 하나의 **기준선 동작**(Task-chat-core 9절, Task-chat-read 4절)을 그대로 흉내 낸다.
 *
 * - 연결 순서: CONNECT → 개인 목적지 2개·내 모든 방 구독 → SEND /app/sync → SYNC → GET /rooms →
 *   (보고 있는 방이면) 최신 메시지 + read-state → 읽음 보고
 * - 보고 있는 방: 받은 메시지로 contiguousTo를 갱신하고, 커지면 **바로** 읽음 보고(HTTP). 빈틈은 1초 기다렸다가
 *   afterSeq로 채운다. 보고 값은 contiguousTo
 * - 전송 창 50(미확정 50개면 그 전송은 막힌 것으로 센다 — 일정표대로 보내는 open-loop라 미뤄 보내지 않는다)
 * - 재연결: 1초 → 2초 → … 최대 30초, 절반~전부 무작위
 *
 * 받은 시각은 WebSocket의 raw `message` 이벤트가 오는 순간 찍는다(STOMP 파싱보다 먼저, 단계 1 C3).
 * 시계는 process.hrtime.bigint() — 프로세스끼리 같은 단조 시계(단계 1 C2에서 확인).
 */
const SEND_WINDOW = 50;
const PAGE = 50;
const GAP_WAIT_MS = 1000;
const HEARTBEAT_MS = 10_000;
const MAX_RECONNECT_MS = 30_000;
export const CONTENT_PREFIX = 'LT1|';

/** 받은 순간을 기록하는 WebSocket. 이 리스너가 STOMP 라이브러리의 onmessage보다 먼저 등록된다. */
function timedSocketClass(headers) {
  return class TimedWebSocket extends WebSocket {
    constructor(url, protocols) {
      super(url, { protocols, headers });
      this.arrivedAt = 0n;
      this.addEventListener('message', () => {
        this.arrivedAt = process.hrtime.bigint();
      });
    }
  };
}

export class Session {
  /**
   * @param ctx 작업 프로세스 공용(지표·보고 기록·설정·HTTP 주소)
   * @param spec { userId, rooms: [roomId], watched: roomId|null, cookie }
   */
  constructor(ctx, spec) {
    this.ctx = ctx;
    this.userId = spec.userId;
    this.rooms = spec.rooms;
    this.watched = spec.watched;
    this.cookie = spec.cookie;
    this.client = null;
    this.ws = null;
    this.running = false;
    this.ready = false;
    this.readyAt = 0n;
    this.reconnectAttempt = 0;
    this.pendingAcks = new Map();   // clientMessageId → 보낸 시각(ns)
    this.syncResolve = null;
    // 보고 있는 방의 기록: 받은 번호, 빈틈 없이 이어진 끝, 마지막 보고
    this.view = null;
    // 받은 READ에서 본 위치(방:사용자 → 위치) — 읽음 반영 지연을 한 번씩만 센다
    this.readSeen = new Map();
  }

  start() {
    this.running = true;
    this.connect();
  }

  stop() {
    this.running = false;
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.view?.gapTimer);
    this.client?.deactivate();
  }

  connect() {
    if (!this.running) return;
    const { config } = this.ctx;
    const Socket = timedSocketClass({ Cookie: this.cookie, Origin: config.origin });
    let opened = false;
    const client = new Client({
      brokerURL: config.wsUrl,
      webSocketFactory: () => {
        this.ws = new Socket(config.wsUrl, ['v12.stomp', 'v11.stomp', 'v10.stomp']);
        return this.ws;
      },
      reconnectDelay: 0,
      heartbeatIncoming: HEARTBEAT_MS,
      heartbeatOutgoing: HEARTBEAT_MS,
      debug: () => {},
    });
    client.onConnect = () => {
      if (this.client !== client) return;
      opened = true;
      this.onConnected(client).catch(() => {});
    };
    client.onStompError = (frame) => this.ctx.count(`stompError:${frame.headers?.message ?? 'unknown'}`);
    client.onWebSocketClose = () => {
      if (this.client !== client) return;
      this.client = null;
      this.ready = false;
      this.syncResolve = null;
      client.deactivate();
      if (!this.running) return;
      this.ctx.count(opened ? 'disconnects' : 'connectFailures');
      if (this.pendingAcks.size > 0) this.ctx.count('acksLostOnDisconnect', this.pendingAcks.size);
      this.pendingAcks.clear();
      const base = Math.min(MAX_RECONNECT_MS, 1000 * 2 ** this.reconnectAttempt);
      this.reconnectAttempt += 1;
      this.reconnectTimer = setTimeout(() => this.connect(), base / 2 + Math.random() * (base / 2));
    };
    this.client = client;
    client.activate();
  }

  async onConnected(client) {
    client.subscribe('/user/queue/acks', (f) => this.onPersonal(f));
    client.subscribe('/user/queue/events', (f) => this.onEvent(f));
    for (const roomId of this.rooms) client.subscribe(`/topic/rooms.${roomId}`, (f) => this.onRoom(f));
    await this.barrier(client);
    await this.http('GET', '/api/chat/rooms');
    if (this.watched != null) await this.openWatched();
    this.reconnectAttempt = 0;
    this.ready = true;
    this.readyAt = process.hrtime.bigint();
    this.ctx.sessionReady(this);
  }

  barrier(client) {
    return new Promise((resolve) => {
      this.syncResolve = resolve;
      client.publish({ destination: '/app/sync', body: '' });
    });
  }

  // ===== 보고 있는 방 =====

  async openWatched() {
    const roomId = this.watched;
    const [page, state] = await Promise.all([
      this.http('GET', `/api/chat/rooms/${roomId}/messages`),
      this.http('GET', `/api/chat/rooms/${roomId}/read-state`),
    ]);
    const view = this.view ?? { received: new Set(), contiguousTo: 0, reported: 0, gapTimer: null, fetching: false };
    this.view = view;
    for (const m of page?.messages ?? []) view.received.add(m.seq);
    // 최신 50개를 기준으로 이어 본다(그 앞은 화면에 없다 — 브라우저와 같다)
    const from = page?.messages?.length ? page.messages[0].seq : (page?.lastSeq ?? 0) + 1;
    view.contiguousTo = Math.max(view.contiguousTo, from - 1);
    if (state) this.applyReadState(roomId, state);
    this.advance();
  }

  advance() {
    const view = this.view;
    let to = view.contiguousTo;
    while (view.received.has(to + 1)) {
      view.received.delete(to + 1);
      to += 1;
    }
    view.contiguousTo = to;
    if (to > view.reported) this.reportRead(to);
    const gap = view.received.size > 0;
    if (gap && !view.gapTimer) {
      view.gapTimer = setTimeout(() => {
        view.gapTimer = null;
        if (view.received.size > 0) this.fillGap();
      }, GAP_WAIT_MS);
    }
  }

  async fillGap() {
    const view = this.view;
    if (view.fetching) return;
    view.fetching = true;
    this.ctx.count('gapFetches');
    try {
      let after = view.contiguousTo;
      for (;;) {
        const page = await this.http('GET', `/api/chat/rooms/${this.watched}/messages?afterSeq=${after}`);
        const msgs = page?.messages ?? [];
        for (const m of msgs) if (m.seq > view.contiguousTo) view.received.add(m.seq);
        if (msgs.length < PAGE) break;
        after = msgs[msgs.length - 1].seq;
      }
    } finally {
      view.fetching = false;
    }
    this.advance();
  }

  /** 바로 보고(디바운스 없음). 실패는 다시 보내지 않는다 — 다음 보고가 덮는다. */
  reportRead(seq) {
    this.view.reported = seq;
    const t0 = process.hrtime.bigint();
    this.ctx.readReported(this.watched, this.userId, seq, t0);
    // 보고 HTTP 왕복(요청 시작 → 응답). 읽음 반영 지연이 HTTP에서 늘었는지 READ 방송에서 늘었는지 나눠 보려고
    this.http('POST', `/api/chat/rooms/${this.watched}/read`, { seq }).then(
      () => {
        this.ctx.count('readReports');
        this.ctx.record('readHttp', process.hrtime.bigint() - t0);
      },
      () => this.ctx.count('readReportErrors'),
    );
  }

  applyReadState(roomId, state) {
    for (const p of state.positions ?? []) {
      const key = `${roomId}:${p.userId}`;
      this.readSeen.set(key, Math.max(this.readSeen.get(key) ?? 0, p.lastReadSeq));
    }
  }

  // ===== 받은 프레임 =====

  onRoom(frame) {
    const at = this.ws?.arrivedAt ?? process.hrtime.bigint();
    const body = this.ctx.parse(frame.body);
    if (body.type === 'READ') {
      this.ctx.count('readFramesReceived');
      this.onRead(body, at);
      return;
    }
    this.ctx.count('messagesReceived');
    if (body.senderId !== this.userId && typeof body.content === 'string' && body.content.startsWith(CONTENT_PREFIX)) {
      const sentAt = BigInt(body.content.slice(CONTENT_PREFIX.length, body.content.indexOf('|', CONTENT_PREFIX.length)));
      this.ctx.record('delivery', at - sentAt);
    }
    if (body.roomId === this.watched && this.view) {
      if (body.seq > this.view.contiguousTo) this.view.received.add(body.seq);
      this.advance();
    }
  }

  onRead(frame, at) {
    for (const p of frame.positions) {
      if (p.userId === this.userId) continue;   // 다른 사람의 보고가 나에게 반영된 시간만 잰다
      const key = `${frame.roomId}:${p.userId}`;
      const prev = this.readSeen.get(key) ?? 0;
      if (p.lastReadSeq <= prev) continue;
      this.readSeen.set(key, p.lastReadSeq);
      this.ctx.readReflected(frame.roomId, p.userId, prev, p.lastReadSeq, at, this.readyAt);
    }
  }

  onPersonal(frame) {
    const at = this.ws?.arrivedAt ?? process.hrtime.bigint();
    const body = JSON.parse(frame.body);
    if (body.type === 'SYNC') {
      this.syncResolve?.();
      this.syncResolve = null;
    } else if (body.type === 'ACK') {
      const sentAt = this.pendingAcks.get(body.clientMessageId);
      if (sentAt != null) {
        this.pendingAcks.delete(body.clientMessageId);
        this.ctx.record('ack', at - sentAt);
        this.ctx.count('acked');
      }
      if (body.roomId === this.watched && this.view && body.seq > this.view.contiguousTo) {
        this.view.received.add(body.seq);
        this.advance();
      }
    } else if (body.type === 'ERROR') {
      this.pendingAcks.delete(body.clientMessageId);
      this.ctx.count(`sendError:${body.code}`);
    }
  }

  onEvent(frame) {
    const body = JSON.parse(frame.body);
    this.ctx.count(`event:${body.type}`);
  }

  // ===== 보내기 =====

  /** @return 'sent' | 'blocked'(전송 창 50) | 'notReady' */
  send(roomId) {
    if (!this.ready || !this.client?.connected) return 'notReady';
    if (this.pendingAcks.size >= SEND_WINDOW) return 'blocked';
    const clientMessageId = randomUUID();
    const sentAt = process.hrtime.bigint();
    const pad = this.ctx.padding;
    this.pendingAcks.set(clientMessageId, sentAt);
    this.client.publish({
      destination: `/app/rooms/${roomId}/messages`,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ clientMessageId, content: `${CONTENT_PREFIX}${sentAt}|${pad}` }),
    });
    return 'sent';
  }

  async http(method, path, body) {
    const { config } = this.ctx;
    const res = await fetch(config.httpUrl + path, {
      method,
      headers: { Cookie: this.cookie, Origin: config.origin, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      this.ctx.count(`http${res.status}:${method}`);
      await res.arrayBuffer().catch(() => {});
      throw new Error(`HTTP ${res.status} ${method} ${path}`);
    }
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }
}

