/**
 * 읽음 보고 묶기 — 방 × 탭 하나의 보고 규칙.
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
 *
 * 서버 쪽 읽음 모으기와 함께 쓰는 규칙(둘 다 설정으로 켜고 끈다. 기본은 위 동작 그대로)
 * 5. 백오프 재시도(retryMode 'backoff'): 4의 재전송을 고정 간격 대신 500ms에서 시작해 2배씩(최대 4초), 0~간격 사이
 *    무작위로 기다린 뒤 보낸다. 기다리는 동안은 창 끝·새 값이 와도 보내지 않는다(그래야 간격이 지켜진다). 성공하면 처음으로.
 *    목적은 안정성: 장애·과부하 때 모든 탭이 같은 박자로 다시 보내 부하를 키우는 되먹임(R2)을 막는다
 * 6. 스냅샷 확인 재전송(confirmMs > 0): 서버가 받았다(202)고 답해도 모아 둔 값을 잃을 수 있다(받은 뒤 서버가 죽음).
 *    받은 뒤 confirmMs(+0~confirmJitterMs 무작위) 안에 READ 스냅샷에서 내 위치가 보낸 값 이상으로 보이지 않으면
 *    다시 보낸다. 최대 confirmMax번, 그 뒤엔 다음 읽음 이벤트(더 큰 값)까지 보류. 스냅샷에서 이미 보낼 값 이상이
 *    보이면 보내지 않는다(observe)
 */
export const RETRY_MS = 500;
export const BACKOFF_START_MS = 500;
export const BACKOFF_MAX_MS = 4000;

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
    /**
     * @param retryMode  'fixed'(4의 규칙) | 'backoff'(5)
     * @param confirmMs  스냅샷 확인 대기(0 = 끔), confirmJitterMs·confirmMax와 함께(6)
     * @param random     () → [0, 1) (테스트에서 주입)
     * @param onConfirmResend (seq, attempt) → 측정용 훅(선택)
     */
    constructor({
        windowMs = 0, retryMs = RETRY_MS, send, timers = globalThis, onResult = null,
        retryMode = 'fixed', confirmMs = 0, confirmJitterMs = 500, confirmMax = 3, random = Math.random, onConfirmResend = null,
    }) {
        this.windowMs = windowMs > 0 ? windowMs : 0;
        this.retryMs = retryMs;
        this.sendFn = send;
        this.timers = timers;
        this.onResult = onResult;
        this.backoff = retryMode === 'backoff';
        this.confirmMs = confirmMs > 0 ? confirmMs : 0;
        this.confirmJitterMs = confirmJitterMs;
        this.confirmMax = confirmMax;
        this.random = random;
        this.onConfirmResend = onConfirmResend;
        this.acked = 0;
        this.dropped = 0;
        this.observed = 0;
        this.target = 0;
        this.inflight = new Set();
        this.windowTimer = null;
        this.retryTimer = null;
        this.failures = 0;
        this.confirm = null;   // { seq, attempts, timer }
        this.disposed = false;
    }

    /** 보낼 값이 남았나: 받아들여지지도, 포기하지도, 스냅샷에 이미 보이지도, 지금 가고 있지도 않은 더 큰 값 */
    needsSend() {
        if (this.target <= Math.max(this.acked, this.dropped, this.observed)) return false;
        for (const seq of this.inflight) if (seq >= this.target) return false;
        return true;
    }

    /** 백오프로 기다리는 중이면 창 끝·새 값으로는 보내지 않는다 */
    mayAct() {
        return this.needsSend() && !(this.backoff && this.retryTimer);
    }

    /** 6. READ 스냅샷·read-state에서 본 내 위치(다른 탭·기기의 보고 포함) */
    observe(seq) {
        if (this.disposed || !(seq > this.observed)) return;
        this.observed = seq;
        if (this.confirm && seq >= this.confirm.seq) this.clearConfirm();
    }

    /** 측정용: 보낸 값이 스냅샷에서 확인됐나(확인 재전송을 켰을 때의 수렴 판정) */
    isConfirmed() {
        return this.observed >= this.target;
    }

    /** 1. 보고할 값이 생김 */
    offer(seq) {
        if (this.disposed) return;
        if (seq > this.target) this.target = seq;
        if (this.windowMs === 0) {
            if (this.mayAct()) this.sendNow();
            return;
        }
        if (this.windowTimer) return;
        if (this.mayAct()) {
            this.sendNow();
            this.openWindow();
        }
    }

    /** 3. 방을 나가거나 탭이 숨겨짐: 남은 값을 바로 보낸다 */
    flush() {
        if (this.disposed || !this.mayAct()) return;
        this.sendNow();
        if (this.windowMs > 0 && !this.windowTimer) this.openWindow();
    }

    dispose() {
        this.disposed = true;
        this.timers.clearTimeout(this.windowTimer);
        this.timers.clearTimeout(this.retryTimer);
        this.windowTimer = null;
        this.retryTimer = null;
        this.clearConfirm();
    }

    /** 측정용: 보낼 값이 남았는데 아무것도 예약되지 않은 상태(= 읽음 위치가 멈춤). 규칙이 맞으면 항상 false */
    isStuck() {
        return this.needsSend() && !this.windowTimer && !this.retryTimer && this.inflight.size === 0;
    }

    clearConfirm() {
        if (this.confirm) this.timers.clearTimeout(this.confirm.timer);
        this.confirm = null;
    }

    /** 6. 받은 값 seq의 확인을 예약한다. 같은 값을 다시 보낸 경우면 앞의 시도 횟수를 잇는다 */
    scheduleConfirm(seq) {
        if (!this.confirmMs || this.observed >= seq) return;
        const attempts = this.confirm && this.confirm.seq === seq ? this.confirm.attempts : 0;
        if (this.confirm && this.confirm.seq > seq) return;   // 더 큰 값을 이미 기다리는 중
        this.clearConfirm();
        const wait = this.confirmMs + this.random() * this.confirmJitterMs;
        this.confirm = { seq, attempts, timer: this.timers.setTimeout(() => this.confirmDue(), wait) };
    }

    confirmDue() {
        const c = this.confirm;
        if (!c || this.disposed) return;
        c.timer = null;
        if (this.observed >= c.seq) {
            this.confirm = null;
            return;
        }
        if (c.attempts >= this.confirmMax) {
            // 보류: 받아들여진 것으로 두고 다음 읽음 이벤트(더 큰 값)를 기다린다
            this.confirm = null;
            return;
        }
        c.attempts += 1;
        this.onConfirmResend?.(c.seq, c.attempts);
        // 서버가 잃었다고 보고 받은 값을 스냅샷에서 본 위치로 되돌린다 → 그 값이 다시 "보낼 값"이 된다
        this.acked = Math.max(this.observed, this.dropped);
        if (this.target < c.seq) this.target = c.seq;
        if (this.mayAct()) this.sendNow();
    }

    /** 4·5. 재전송 예약 */
    scheduleRetry() {
        if (this.backoff) {
            if (this.retryTimer) return;
            const cap = Math.min(BACKOFF_MAX_MS, BACKOFF_START_MS * 2 ** this.failures);
            this.failures += 1;
            this.retryTimer = this.timers.setTimeout(() => {
                this.retryTimer = null;
                if (this.disposed || !this.needsSend()) return;
                this.sendNow();
                if (this.windowMs > 0 && !this.windowTimer) this.openWindow();
            }, this.random() * cap);
            return;
        }
        if (this.windowMs > 0) {
            if (!this.windowTimer) this.openWindow();
        } else if (!this.retryTimer) {
            this.retryTimer = this.timers.setTimeout(() => {
                this.retryTimer = null;
                if (!this.disposed && this.needsSend()) this.sendNow();
            }, this.retryMs);
        }
    }

    openWindow() {
        this.windowTimer = this.timers.setTimeout(() => this.windowEnd(), this.windowMs);
    }

    /** 2. 창이 끝남 */
    windowEnd() {
        this.windowTimer = null;
        if (this.disposed) return;
        if (this.mayAct()) {
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
            this.failures = 0;
        } else if (kind === 'drop') {
            if (seq > this.dropped) this.dropped = seq;
        }
        this.onResult?.(seq, kind, err);
        if (this.disposed) return;
        if (kind === 'ok') {
            this.scheduleConfirm(seq);
            return;
        }
        if (kind !== 'retry') return;
        // 거절된 값은 target에 남아 있다(target ≥ seq). 다음 기회를 예약한다
        this.scheduleRetry();
    }
}
