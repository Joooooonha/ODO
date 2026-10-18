import { Client } from '@stomp/stompjs';
import { getChatMessages, getChatRooms } from '../api/client';
import { loadOutbox, saveOutbox } from './outbox';
import { MAX_MESSAGE_LENGTH, codePointLength, newClientMessageId } from './format';

/**
 * 브라우저 쪽 채팅 상태와 연결(9절). React 밖의 단순 저장소이고, 화면은 useSyncExternalStore로 구독한다.
 *
 * 핵심 규칙
 * - **원본은 서버 DB다.** HTTP 응답과 소켓 프레임은 같은 저장소에 들어오는 두 출처이고, 같은 규칙으로
 *   넣는다(seq를 키로). 도착 순서와 무관하게 결과가 같다(9.3)
 * - **구독 → SYNC → HTTP 조회** 순서(9.2·9.4). 메모리 브로커는 SUBSCRIBE에 확인을 주지 않으므로
 *   SEND /app/sync의 응답(SYNC)을 받은 뒤에야 "앞의 구독은 등록됐다"고 본다. 구독 등록 전에 나간 메시지·
 *   알림은 그 뒤의 조회가 채운다. 연결 직후, 새 방 구독, GRANTED, 방 열기, 재연결 모두 이 순서를 쓴다
 * - 화면은 "도착 순서대로 붙인 목록"이 아니라 "저장소를 seq로 정렬해 기준(baseline)부터 빈틈 없이 이어진
 *   곳까지 그린 결과"다. 빈틈은 1초 기다렸다가 afterSeq로 채운다(9.4)
 * - 보낼 목록(outbox): 확정 응답을 받아야만 지운다. 확정을 기다리지 않고 연달아 보내되 미확정 50개(전송
 *   창)에서 멈춘다. 재전송은 재연결과 SERVER_RETRY_EXHAUSTED 두 경우뿐이다(9.5)
 */

export const SEND_WINDOW = 50;
const PAGE = 50;
const GAP_WAIT_MS = 1000;
const AUTO_RESEND_MS = 5 * 60 * 1000;
const HEARTBEAT_MS = 10_000;
const MAX_RECONNECT_MS = 30_000;
const MAX_RESUME_MS = 30_000;

/** 메시지 한 건(서버 응답·방송·확정을 같은 모양으로). */
const normalize = (m) => ({
    seq: m.seq,
    messageId: m.messageId,
    type: m.type,
    senderId: m.senderId ?? null,
    senderNickname: m.senderNickname ?? null,
    content: m.content,
    createdAt: m.createdAt,
    clientMessageId: m.clientMessageId ?? null,
    targetNickname: m.targetNickname ?? null,
});

export class ChatStore {
    constructor({ socketUrl }) {
        this.socketUrl = socketUrl;
        this.listeners = new Set();
        this.version = 0;

        this.user = null;
        this.running = false;
        /** idle | connecting | connected | reconnecting */
        this.status = 'idle';
        this.client = null;
        this.reconnectAttempt = 0;
        this.reconnectTimer = null;
        this.roomSubscriptions = new Map();
        this.syncWaiters = [];

        this.rooms = new Map();
        this.roomsLoaded = false;
        this.roomOrder = new Map();
        this.openRooms = new Map();
        this.revokedRooms = new Set();
        this.notice = '';

        this.outbox = [];
        this.halted = false;
        this.resumeAttempt = 0;
        this.resumeTimer = null;

        this.authCheck = () => {};
    }

    // ===== React 연결 =====

    subscribe = (listener) => {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    };

    getVersion = () => this.version;

    emit() {
        this.version += 1;
        this.listeners.forEach((l) => l());
    }

    setAuthCheck(fn) {
        this.authCheck = fn;
    }

    // ===== 시작·종료 =====

    /** 로그인한 사용자로 연결을 시작한다. 새로 고침으로 남은 보낼 목록은 자동으로 보내지 않는다(held). */
    start(user) {
        if (this.running && this.user?.id === user.id) return;
        this.stop();
        this.user = user;
        this.running = true;
        this.outbox = loadOutbox(user.id).map((item) => (item.state === 'pending' ? { ...item, state: 'held' } : item));
        this.connect();
    }

    /** 연결을 끊는다(로그아웃·화면 종료). 쿠키 삭제만으로는 열린 연결이 남으므로 직접 끊는다. */
    stop() {
        this.running = false;
        clearTimeout(this.reconnectTimer);
        clearTimeout(this.resumeTimer);
        this.rejectSyncWaiters();
        const client = this.client;
        this.client = null;
        client?.deactivate();
        this.roomSubscriptions.clear();
        this.rooms.forEach((room) => clearTimeout(room.gapTimer));
        this.rooms = new Map();
        this.roomsLoaded = false;
        this.status = 'idle';
        this.halted = false;
        this.emit();
    }

    /** 로그아웃: 보낼 목록도 지운다. */
    clearOutbox() {
        this.outbox = [];
        saveOutbox(null, []);
        this.emit();
    }

    // ===== 연결 =====

    connect() {
        if (!this.running) return;
        this.status = this.reconnectAttempt === 0 && this.status === 'idle' ? 'connecting' : 'reconnecting';
        this.emit();

        let opened = false;
        const client = new Client({
            brokerURL: this.socketUrl,
            // 라이브러리의 재연결은 쓰지 않는다 — 지터를 섞은 지수 대기로 직접 한다(9.2)
            reconnectDelay: 0,
            heartbeatIncoming: HEARTBEAT_MS,
            heartbeatOutgoing: HEARTBEAT_MS,
            debug: () => {},
        });
        client.onConnect = () => {
            if (this.client !== client) return;
            opened = true;
            this.onConnected(client);
        };
        client.onStompError = (frame) => {
            const reason = frame.headers?.message;
            if (reason === 'QUEUE_LIMIT_EXCEEDED') {
                // 정상 브라우저에서는 일어나면 안 된다(전송 창 50을 지키므로) — 버그 신호
                console.error('[채팅] 서버가 대기열 상한 초과로 연결을 끊었습니다. 전송 창 규칙을 확인하세요.');
            } else if (reason === 'TOKEN_EXPIRED') {
                this.authCheck();
            }
        };
        client.onWebSocketClose = () => {
            if (this.client !== client) return;
            this.client = null;
            client.deactivate();
            this.roomSubscriptions.clear();
            this.rejectSyncWaiters();
            this.halted = false;                 // 멈춤은 세션의 상태다. 새 세션은 멈춤 없이 시작
            clearTimeout(this.resumeTimer);
            if (!opened) {
                // 브라우저는 핸드셰이크의 HTTP 상태(401)를 볼 수 없다 → 로그인이 살아 있는지 직접 묻는다
                this.authCheck();
            }
            this.status = 'reconnecting';
            this.emit();
            this.scheduleReconnect();
        };
        this.client = client;
        client.activate();
    }

    /** 1초 → 2초 → 4초 … 최대 30초, 매번 무작위로 절반~전부(동시에 재연결이 몰리지 않게). */
    scheduleReconnect() {
        if (!this.running) return;
        const base = Math.min(MAX_RECONNECT_MS, 1000 * 2 ** this.reconnectAttempt);
        this.reconnectAttempt += 1;
        const delay = base / 2 + Math.random() * (base / 2);
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = setTimeout(() => this.connect(), delay);
    }

    async onConnected(client) {
        this.reconnectAttempt = 0;
        client.subscribe('/user/queue/acks', (frame) => this.onPersonal(JSON.parse(frame.body)));
        client.subscribe('/user/queue/events', (frame) => this.onEvent(JSON.parse(frame.body)));
        this.rooms.forEach((_, roomId) => this.subscribeRoom(roomId));

        try {
            await this.barrier();
            const added = await this.refreshRooms();
            if (added.length > 0) await this.barrier();
        } catch {
            return;   // 그사이 끊겼다 — 재연결이 처음부터 다시 한다
        }
        this.status = 'connected';
        this.emit();
        // 보낼 목록 전체를 원래 순서대로, 같은 UUID로, 새 메시지보다 먼저(9.5 ①)
        this.transmitAll(false);
        this.openRooms.forEach((_, roomId) => this.catchUp(roomId));
    }

    /** 구독 장벽: SEND /app/sync → SYNC가 오면 그 전에 보낸 구독은 모두 등록됐다. */
    barrier() {
        return new Promise((resolve, reject) => {
            if (!this.client?.connected) {
                reject(new Error('disconnected'));
                return;
            }
            this.syncWaiters.push({ resolve, reject });
            this.client.publish({ destination: '/app/sync', body: '' });
        });
    }

    rejectSyncWaiters() {
        const waiters = this.syncWaiters;
        this.syncWaiters = [];
        waiters.forEach((w) => w.reject(new Error('disconnected')));
    }

    subscribeRoom(roomId) {
        if (!this.client?.connected || this.roomSubscriptions.has(roomId)) return false;
        const sub = this.client.subscribe(`/topic/rooms.${roomId}`, (frame) => this.onRoomMessage(JSON.parse(frame.body)));
        this.roomSubscriptions.set(roomId, sub);
        return true;
    }

    // ===== 방 목록 =====

    /** GET /rooms를 합치고, 새로 알게 된 방을 구독한다. 구독을 새로 한 방 id들을 돌려준다. */
    async refreshRooms() {
        const list = await getChatRooms();
        const seen = new Set();
        const added = [];
        list.forEach((summary, index) => {
            seen.add(summary.roomId);
            this.roomOrder.set(summary.roomId, index);
            this.mergeSummary(summary);
            if (this.subscribeRoom(summary.roomId)) added.push(summary.roomId);
        });
        // 목록에 없는 방은 그사이 나가거나 추방됐다
        [...this.rooms.keys()].filter((id) => !seen.has(id)).forEach((id) => this.dropRoom(id, false));
        this.roomsLoaded = true;
        this.emit();
        return added;
    }

    ensureRoom(roomId) {
        let room = this.rooms.get(roomId);
        if (!room) {
            room = {
                roomId, type: null, displayName: '', teamId: null, peerUserId: null, empty: false,
                lastSeq: 0, myReadSeq: null, preview: null, previewSeq: 0,
                messages: new Map(), baseline: null, contiguousTo: 0, hasOlder: false,
                loading: false, loadingOlder: false, error: '', gapTimer: null, fetching: new Set(),
            };
            this.rooms.set(roomId, room);
        }
        return room;
    }

    mergeSummary(s) {
        const room = this.ensureRoom(s.roomId);
        room.type = s.type;
        room.displayName = s.displayName;
        room.teamId = s.teamId ?? null;
        room.peerUserId = s.peerUserId ?? null;
        room.myReadSeq = s.myReadSeq;
        room.lastSeq = Math.max(room.lastSeq, s.lastSeq);
        room.empty = s.empty && room.lastSeq === 0;
        // 목록은 더 큰 seq일 때만 바꾼다(서버 간 발행 순서가 뒤바뀔 수 있다)
        if (s.lastMessage && s.lastMessage.seq > room.previewSeq) {
            room.preview = normalize(s.lastMessage);
            room.previewSeq = s.lastMessage.seq;
        }
    }

    /** 화면에 보일 방 목록: 빈 DM은 숨긴다(E1). 최근 메시지 순, 메시지 없는 방은 서버 순서. */
    roomList() {
        return [...this.rooms.values()]
            .filter((r) => r.type && !(r.type === 'DM' && r.empty))
            .sort((a, b) => {
                const ta = a.preview?.createdAt ? Date.parse(a.preview.createdAt) : 0;
                const tb = b.preview?.createdAt ? Date.parse(b.preview.createdAt) : 0;
                if (ta !== tb) return tb - ta;
                return (this.roomOrder.get(a.roomId) ?? 0) - (this.roomOrder.get(b.roomId) ?? 0);
            });
    }

    /** 안 읽은 수는 저장하지 않고 계산한다. 읽은 위치를 모르면 보류(null). */
    unread(room) {
        return room.myReadSeq == null ? null : Math.max(0, room.lastSeq - room.myReadSeq);
    }

    dropRoom(roomId, revoked) {
        const room = this.rooms.get(roomId);
        if (room) clearTimeout(room.gapTimer);
        this.rooms.delete(roomId);
        this.roomSubscriptions.get(roomId)?.unsubscribe();
        this.roomSubscriptions.delete(roomId);
        if (revoked) {
            this.revokedRooms.add(roomId);
            // 그 방으로 보내려던 것은 더 보낼 수 없다
            this.outbox.forEach((item) => {
                if (item.roomId === roomId && item.state === 'pending') item.state = 'failed';
            });
            saveOutbox(this.user?.id, this.outbox);
        }
    }

    // ===== 방 열기·기록 =====

    /** 방 화면이 열릴 때. 구독 → SYNC → 최신 50(또는 이어 받기). */
    async openRoom(roomId) {
        this.openRooms.set(roomId, (this.openRooms.get(roomId) ?? 0) + 1);
        this.revokedRooms.delete(roomId);
        this.ensureRoom(roomId);
        this.emit();
        if (this.status !== 'connected') return;   // 연결되면 onConnected가 이어 받는다
        const subscribedNow = this.subscribeRoom(roomId);
        try {
            if (subscribedNow) await this.barrier();
        } catch {
            return;
        }
        await this.catchUp(roomId);
    }

    closeRoom(roomId) {
        const n = (this.openRooms.get(roomId) ?? 1) - 1;
        if (n <= 0) this.openRooms.delete(roomId);
        else this.openRooms.set(roomId, n);
    }

    /** 기준이 없으면 최신 50, 있으면 마지막으로 받은 번호 이후. 너무 벌어졌으면 최신 50부터 다시. */
    async catchUp(roomId) {
        const room = this.rooms.get(roomId);
        if (!room) return;
        if (!room.baseline || room.lastSeq - room.contiguousTo > PAGE) {
            await this.loadLatest(room);
        } else {
            await this.fetchAfter(room, room.contiguousTo);
        }
    }

    async loadLatest(room) {
        if (room.loading) return;
        room.loading = true;
        room.error = '';
        this.emit();
        try {
            const page = await getChatMessages(room.roomId);
            page.messages.forEach((m) => this.putMessage(room, normalize(m)));
            room.lastSeq = Math.max(room.lastSeq, page.lastSeq);
            const from = page.messages.length > 0 ? page.messages[0].seq : page.lastSeq + 1;
            room.baseline = { from };
            room.contiguousTo = from - 1;
            this.extendBaselineDown(room);
            this.recompute(room);
            room.hasOlder = room.baseline.from > 1;
            this.reconcileHeld(page.messages);
        } catch (err) {
            this.onRoomLoadError(room, err);
        } finally {
            room.loading = false;
            this.emit();
        }
    }

    /** 위로 스크롤: 기준보다 앞의 50개. */
    async loadOlder(roomId) {
        const room = this.rooms.get(roomId);
        if (!room?.baseline || room.loadingOlder || room.baseline.from <= 1) return;
        room.loadingOlder = true;
        this.emit();
        try {
            const page = await getChatMessages(roomId, { beforeSeq: room.baseline.from });
            page.messages.forEach((m) => this.putMessage(room, normalize(m)));
            if (page.messages.length > 0) room.baseline.from = page.messages[0].seq;
            this.extendBaselineDown(room);
            room.hasOlder = room.baseline.from > 1;
            this.reconcileHeld(page.messages);
        } catch (err) {
            this.onRoomLoadError(room, err);
        } finally {
            room.loadingOlder = false;
            this.emit();
        }
    }

    /** 빈틈 채우기·재연결 이어 받기. 같은 범위는 한 번만 조회한다. */
    async fetchAfter(room, afterSeq) {
        const key = `after:${afterSeq}`;
        if (room.fetching.has(key)) return;
        room.fetching.add(key);
        try {
            let after = afterSeq;
            for (;;) {
                const page = await getChatMessages(room.roomId, { afterSeq: after });
                page.messages.forEach((m) => this.putMessage(room, normalize(m)));
                room.lastSeq = Math.max(room.lastSeq, page.lastSeq);
                this.reconcileHeld(page.messages);
                if (page.messages.length < PAGE) break;
                after = page.messages[page.messages.length - 1].seq;
            }
            this.recompute(room);
        } catch (err) {
            this.onRoomLoadError(room, err);
        } finally {
            room.fetching.delete(key);
            this.emit();
        }
    }

    onRoomLoadError(room, err) {
        if (err?.status === 403) {
            this.dropRoom(room.roomId, true);
        } else {
            room.error = '메시지를 불러오지 못했어요.';
        }
    }

    // ===== 합치기 규칙 =====

    putMessage(room, message) {
        if (!room.messages.has(message.seq)) {
            room.messages.set(message.seq, message);
        }
        room.lastSeq = Math.max(room.lastSeq, message.seq);
        room.empty = false;
        if (message.seq > room.previewSeq) {
            room.preview = message;
            room.previewSeq = message.seq;
        }
    }

    /** 기준 아래로 이미 가진 메시지가 이어지면 기준을 내린다(예전에 받은 과거 메시지와 다시 이어질 때). */
    extendBaselineDown(room) {
        while (room.baseline.from > 1 && room.messages.has(room.baseline.from - 1)) {
            room.baseline.from -= 1;
        }
    }

    /** 기준부터 빈틈 없이 이어진 끝을 다시 잰다. 그 뒤에 메시지가 있으면(빈틈) 1초 기다렸다가 조회. */
    recompute(room) {
        if (!room.baseline) return;
        let to = Math.max(room.contiguousTo, room.baseline.from - 1);
        while (room.messages.has(to + 1)) to += 1;
        room.contiguousTo = to;
        const hasGap = [...room.messages.keys()].some((seq) => seq > to + 1)
            || room.lastSeq > to;
        if (hasGap && !room.gapTimer) {
            room.gapTimer = setTimeout(() => {
                room.gapTimer = null;
                this.recompute(room);
                if (room.lastSeq > room.contiguousTo || [...room.messages.keys()].some((s) => s > room.contiguousTo + 1)) {
                    this.fetchAfter(room, room.contiguousTo);
                }
            }, GAP_WAIT_MS);
        }
    }

    /** 화면에 그릴 메시지: 기준부터 빈틈 없이 이어진 곳까지, seq 순. */
    visibleMessages(roomId) {
        const room = this.rooms.get(roomId);
        if (!room?.baseline) return [];
        const out = [];
        for (let seq = room.baseline.from; seq <= room.contiguousTo; seq += 1) {
            const m = room.messages.get(seq);
            if (m) out.push(m);
        }
        return out;
    }

    // ===== 소켓 프레임 =====

    onRoomMessage(message) {
        const room = this.rooms.get(message.roomId);
        if (!room) return;
        const m = normalize(message);
        if (room.baseline || room.loading) {
            this.putMessage(room, m);
            this.recompute(room);
        } else {
            room.lastSeq = Math.max(room.lastSeq, m.seq);
            room.empty = false;
            if (m.seq > room.previewSeq) {
                room.preview = m;
                room.previewSeq = m.seq;
            }
        }
        // 내 메시지가 방송으로 먼저 왔으면(확정보다) 보낼 목록에서도 지운다 — 같은 seq라 중복 표시되지 않는다
        if (m.clientMessageId && m.senderId === this.user?.id) this.removeFromOutbox(m.clientMessageId);
        this.emit();
    }

    onPersonal(frame) {
        if (frame.type === 'SYNC') {
            this.syncWaiters.shift()?.resolve();
            return;
        }
        if (frame.type === 'ACK') {
            const item = this.outbox.find((i) => i.clientMessageId === frame.clientMessageId);
            const room = this.rooms.get(frame.roomId);
            if (room && item) {
                const m = {
                    seq: frame.seq, messageId: null, type: 'TEXT', senderId: this.user?.id ?? null,
                    senderNickname: this.user?.nickname ?? null, content: item.content, createdAt: frame.createdAt,
                    clientMessageId: frame.clientMessageId, targetNickname: null,
                };
                if (room.baseline || room.loading) {
                    this.putMessage(room, m);
                    this.recompute(room);
                } else if (m.seq > room.previewSeq) {
                    room.preview = m;
                    room.previewSeq = m.seq;
                    room.lastSeq = Math.max(room.lastSeq, m.seq);
                }
            }
            this.removeFromOutbox(frame.clientMessageId);
            this.resumeAttempt = 0;
            this.emit();
            return;
        }
        if (frame.type === 'ERROR') {
            if (frame.code === 'SERVER_RETRY_EXHAUSTED') {
                this.onHalted();
                return;
            }
            // NOT_MEMBER · INVALID_MESSAGE · INTERNAL_ERROR: 그 메시지만 실패로 두고 다음으로 진행
            const item = this.outbox.find((i) => i.clientMessageId === frame.clientMessageId);
            if (item) {
                item.state = 'failed';
                item.error = frame.code;
                saveOutbox(this.user?.id, this.outbox);
            }
            this.emit();
        }
    }

    onEvent(frame) {
        if (frame.type === 'REVOKED') {
            this.dropRoom(frame.roomId, true);
            this.emit();
        } else if (frame.type === 'GRANTED') {
            this.onGranted(frame.roomId);
        }
    }

    /** 새 방(참여·DM): 구독 → SYNC → 목록 다시 읽기(방 정보) → 열려 있으면 기록. */
    async onGranted(roomId) {
        this.revokedRooms.delete(roomId);
        const subscribedNow = this.subscribeRoom(roomId);
        try {
            if (subscribedNow) await this.barrier();
            await this.refreshRooms();
        } catch {
            return;
        }
        if (this.openRooms.has(roomId)) await this.catchUp(roomId);
    }

    // ===== 보내기 =====

    pendingCount() {
        return this.outbox.filter((i) => i.state === 'pending').length;
    }

    /** 전송 창이 찼는가(미확정 50개). 차면 전송 버튼을 잠근다. */
    windowFull() {
        return this.pendingCount() >= SEND_WINDOW;
    }

    canTransmit() {
        return this.status === 'connected' && this.client?.connected && !this.halted;
    }

    /** @return 받아들였으면 true(보낼 목록에 넣음). 입력 오류·창이 차면 false — 입력창 글은 그대로 둔다 */
    send(roomId, content) {
        const length = codePointLength(content);
        if (!content || !content.trim() || length > MAX_MESSAGE_LENGTH || this.windowFull()) return false;
        const item = {
            clientMessageId: newClientMessageId(), roomId, content, createdAt: Date.now(), state: 'pending',
        };
        this.outbox.push(item);
        saveOutbox(this.user?.id, this.outbox);
        if (this.canTransmit()) this.transmit(item, false);
        this.emit();
        return true;
    }

    /**
     * 실제로 보낸다. 작성 5분이 지났으면 자동으로 보내지 않는다 — 검사는 보내기 직전에 한다(지우기가
     * 실패해도 오래된 메시지가 자동 전송되지 않게).
     */
    transmit(item, resume) {
        if (Date.now() - item.createdAt > AUTO_RESEND_MS) {
            item.state = 'expired';
            saveOutbox(this.user?.id, this.outbox);
            return false;
        }
        const body = { clientMessageId: item.clientMessageId, content: item.content };
        if (resume) body.resume = true;
        this.client.publish({
            destination: `/app/rooms/${item.roomId}/messages`,
            body: JSON.stringify(body),
            headers: { 'content-type': 'application/json' },
        });
        return true;
    }

    /** 보낼 목록 전체를 처음부터 원래 순서로. resume은 그중 처음 보내는 하나에만. */
    transmitAll(withResume) {
        if (!this.canTransmit()) return;
        let resume = withResume;
        this.outbox.forEach((item) => {
            if (item.state === 'pending' && this.transmit(item, resume)) resume = false;
        });
        this.emit();
    }

    /** SERVER_RETRY_EXHAUSTED: 서버가 이 세션을 멈췄다. 2초 → 4초 … 뒤 첫 메시지에 resume을 붙여 전체 재전송. */
    onHalted() {
        if (this.halted) return;
        this.halted = true;
        const delay = Math.min(MAX_RESUME_MS, 2000 * 2 ** this.resumeAttempt);
        this.resumeAttempt += 1;
        clearTimeout(this.resumeTimer);
        this.resumeTimer = setTimeout(() => {
            if (!this.client?.connected || this.status !== 'connected') return;   // 재연결이 대신 처리한다
            this.halted = false;
            this.transmitAll(true);
        }, delay);
        this.emit();
    }

    /** 사용자가 고른 다시 보내기(실패·만료·새로 고침 뒤 남은 것). 작성 시각을 새로 잡는다. */
    retry(clientMessageId) {
        const item = this.outbox.find((i) => i.clientMessageId === clientMessageId);
        if (!item || item.state === 'pending' || this.windowFull()) return;
        item.state = 'pending';
        item.error = undefined;
        item.createdAt = Date.now();
        saveOutbox(this.user?.id, this.outbox);
        if (this.canTransmit()) this.transmit(item, false);
        this.emit();
    }

    discard(clientMessageId) {
        this.removeFromOutbox(clientMessageId);
        this.emit();
    }

    removeFromOutbox(clientMessageId) {
        const before = this.outbox.length;
        this.outbox = this.outbox.filter((i) => i.clientMessageId !== clientMessageId);
        if (this.outbox.length !== before) saveOutbox(this.user?.id, this.outbox);
    }

    /** 새로 고침 뒤 남은 것 중 이미 저장된 것(본인 UUID로 확인)은 지운다. */
    reconcileHeld(messages) {
        const saved = new Set(messages.filter((m) => m.clientMessageId).map((m) => m.clientMessageId));
        if (saved.size === 0) return;
        const before = this.outbox.length;
        this.outbox = this.outbox.filter((i) => !saved.has(i.clientMessageId));
        if (this.outbox.length !== before) saveOutbox(this.user?.id, this.outbox);
    }

    /** 이 방의 보낼 목록(화면 아래에 순서대로 그린다). */
    outboxOf(roomId) {
        return this.outbox.filter((i) => i.roomId === roomId);
    }
}
