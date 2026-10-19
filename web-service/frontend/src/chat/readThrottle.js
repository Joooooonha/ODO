/**
 * 읽음 보고 묶기(Task-chat-read-improve 2절, 개선 B) — 방 × 탭 하나의 보고 규칙.
 *
 * 화면(chatStore)과 부하 생성기(load-test/src/session.mjs)가 **이 파일을 그대로** 같이 쓴다. 생성기는 브라우저를
 * 흉내 내는 측정이라 규칙이 한 글자라도 다르면 안 된다. 그래서 브라우저·Node 어느 한쪽에만 있는 API를 쓰지 않는다
 * (타이머는 주입받는다).
 *
 * 상태: acked(서버가 204로 받아들인 마지막 값), target(보낼 값 = 화면에 붙은 가장 큰 contiguousTo), 창(window)
 * 1. 보고할 값이 생김(offer): target을 키운다. 창이 닫혀 있으면 **바로 보내고** W ms 창을 연다(앞쪽 즉시 전송).
 *    창이 열려 있으면 보내지 않는다
 * 2. 창이 끝남: 보낼 값이 남아 있으면 보내고 창을 다시 연다. 아니면 창을 닫는다(뒤쪽 마지막 값 전송)
 * 3. 방을 나감·탭이 숨겨짐(flush): 보낼 값이 남아 있으면 **바로** 보낸다(사용자가 실제로 본 위치)
 * 4. 응답: 204 → acked를 키운다. 429(벌크헤드 거절)·네트워크 실패·5xx → 그 값은 target에 그대로 남아(최대값 유지)
 *    다음 창에 다시 나간다. W = 0이면 500ms 뒤에 다시 보낸다(실패 한 번에 재전송 한 번, 재전송이 실패하면 같은 규칙).
 *    400·403 등 → 다시 보내지 않는다(다시 보내도 같은 결과)
 *
 * 왜 거절·실패하면 다시 보내나: 기준선 규칙은 "실패하면 다음 보고가 덮는다"였는데, 묶으면 창 끝의 마지막 보고가
 * 거절된 뒤 방에 새 메시지가 없을 때 "다음 보고"가 오지 않아 서버의 읽음 위치가 영원히 멈춘다.
 *
 * W = 0이면 기준선과 같다: 값이 커질 때마다 바로 보내고, 같은 값을 두 번 보내지 않는다(실패했을 때의 재전송만 다르다).
 */
export const RETRY_MS = 500;

/** 응답 분류. 성공 → 'ok', 429·네트워크 실패(상태 없음)·5xx → 'retry', 그 밖의 4xx → 'drop'. */
export function classifyReadResult(err) {
    if (!err) return 'ok';
    const status = err.status;
    if (status == null || status === 429 || status >= 500) return 'retry';
    return 'drop';
}

export class ReadThrottle {
    /**
     * @param windowMs 묶기 간격 W(0 = 묶지 않음)
     * @param send     (seq) → Promise. 성공하면 resolve, 실패하면 {status?}를 담은 오류로 reject
     * @param timers   { setTimeout, clearTimeout }
     * @param onResult (seq, 'ok'|'retry'|'drop', err) → 측정용 훅(선택)
     */
    constructor({ windowMs = 0, retryMs = RETRY_MS, send, timers = globalThis, onResult = null }) {
        this.windowMs = windowMs > 0 ? windowMs : 0;
        this.retryMs = retryMs;
        this.sendFn = send;
        this.timers = timers;
        this.onResult = onResult;
        this.acked = 0;
        this.dropped = 0;
        this.target = 0;
        this.inflight = new Set();
        this.windowTimer = null;
        this.retryTimer = null;
        this.disposed = false;
    }

    /** 보낼 값이 남았나: 받아들여지지도, 포기하지도, 지금 가고 있지도 않은 더 큰 값 */
    needsSend() {
        if (this.target <= Math.max(this.acked, this.dropped)) return false;
        for (const seq of this.inflight) if (seq >= this.target) return false;
        return true;
    }

    /** 1. 보고할 값이 생김 */
    offer(seq) {
        if (this.disposed) return;
        if (seq > this.target) this.target = seq;
        if (this.windowMs === 0) {
            if (this.needsSend()) this.sendNow();
            return;
        }
        if (this.windowTimer) return;
        if (this.needsSend()) {
            this.sendNow();
            this.openWindow();
        }
    }

    /** 3. 방을 나가거나 탭이 숨겨짐: 남은 값을 바로 보낸다 */
    flush() {
        if (this.disposed || !this.needsSend()) return;
        this.sendNow();
        if (this.windowMs > 0 && !this.windowTimer) this.openWindow();
    }

    dispose() {
        this.disposed = true;
        this.timers.clearTimeout(this.windowTimer);
        this.timers.clearTimeout(this.retryTimer);
        this.windowTimer = null;
        this.retryTimer = null;
    }

    /** 측정용: 보낼 값이 남았는데 아무것도 예약되지 않은 상태(= 읽음 위치가 멈춤). 규칙이 맞으면 항상 false */
    isStuck() {
        return this.needsSend() && !this.windowTimer && !this.retryTimer && this.inflight.size === 0;
    }

    openWindow() {
        this.windowTimer = this.timers.setTimeout(() => this.windowEnd(), this.windowMs);
    }

    /** 2. 창이 끝남 */
    windowEnd() {
        this.windowTimer = null;
        if (this.disposed) return;
        if (this.needsSend()) {
            this.sendNow();
            this.openWindow();
        }
    }

    sendNow() {
        const seq = this.target;
        this.inflight.add(seq);
        let result;
        try {
            result = Promise.resolve(this.sendFn(seq));
        } catch (err) {
            result = Promise.reject(err);
        }
        result.then(() => this.settle(seq, null), (err) => this.settle(seq, err ?? {}));
    }

    /** 4. 응답 처리 */
    settle(seq, err) {
        this.inflight.delete(seq);
        const kind = classifyReadResult(err);
        if (kind === 'ok') {
            if (seq > this.acked) this.acked = seq;
        } else if (kind === 'drop') {
            if (seq > this.dropped) this.dropped = seq;
        }
        this.onResult?.(seq, kind, err);
        if (kind !== 'retry' || this.disposed) return;
        // 거절된 값은 target에 남아 있다(target ≥ seq). 다음 기회를 예약한다
        if (this.windowMs > 0) {
            if (!this.windowTimer) this.openWindow();
        } else if (!this.retryTimer) {
            this.retryTimer = this.timers.setTimeout(() => {
                this.retryTimer = null;
                if (!this.disposed && this.needsSend()) this.sendNow();
            }, this.retryMs);
        }
    }
}
