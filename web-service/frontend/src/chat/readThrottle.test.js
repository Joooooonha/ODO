// 읽음 보고 묶기 규칙(readThrottle.js) 단위 테스트. 실행: node --test src/chat/readThrottle.test.js
// 화면과 부하 생성기가 같은 파일을 쓰므로 여기서 한 번 검증한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ReadThrottle, classifyReadResult } from './readThrottle.js';

/** 직접 돌리는 시계: advance(ms)로 예약된 타이머를 시각 순서대로 울린다. */
function fakeTimers() {
    let now = 0;
    let nextId = 1;
    const timers = new Map();
    return {
        setTimeout(fn, ms) {
            const id = nextId++;
            timers.set(id, { at: now + ms, fn });
            return id;
        },
        clearTimeout(id) {
            timers.delete(id);
        },
        async advance(ms) {
            const end = now + ms;
            for (;;) {
                const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
                if (!due) break;
                timers.delete(due[0]);
                now = due[1].at;
                due[1].fn();
                await flushPromises();
            }
            now = end;
        },
        get pending() {
            return timers.size;
        },
    };
}

const flushPromises = async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
};

/** 보낸 값을 기록하고, 응답은 테스트가 정한다(기본 204). */
function server(timers, windowMs) {
    const sent = [];
    let respond = () => Promise.resolve();
    const throttle = new ReadThrottle({
        windowMs,
        timers,
        send: (seq) => {
            sent.push(seq);
            return respond(seq);
        },
    });
    return { throttle, sent, setRespond: (fn) => { respond = fn; } };
}

const rejectWith = (status) => () => Promise.reject(Object.assign(new Error(`HTTP ${status}`), { status }));

test('W = 0: 값이 커질 때마다 바로 보내고, 같은 값은 다시 보내지 않는다(기준선과 같다)', async () => {
    const t = fakeTimers();
    const { throttle, sent } = server(t, 0);
    throttle.offer(1);
    throttle.offer(2);
    throttle.offer(2);
    throttle.offer(1);
    await flushPromises();
    throttle.offer(3);
    await flushPromises();
    assert.deepEqual(sent, [1, 2, 3]);
    assert.equal(throttle.acked, 3);
    assert.equal(t.pending, 0);
});

test('W = 500: 창이 닫혀 있으면 바로 보내고(앞쪽), 창 안의 값은 창 끝에 마지막 값만 보낸다(뒤쪽)', async () => {
    const t = fakeTimers();
    const { throttle, sent } = server(t, 500);
    throttle.offer(1);
    assert.deepEqual(sent, [1], '앞쪽 즉시 전송');
    for (let s = 2; s <= 9; s++) throttle.offer(s);
    await t.advance(499);
    assert.deepEqual(sent, [1], '창 안에서는 보내지 않는다');
    await t.advance(1);
    assert.deepEqual(sent, [1, 9], '창 끝에 마지막 값');
    await t.advance(500);
    assert.deepEqual(sent, [1, 9], '보낼 값이 없으면 창을 닫는다');
    assert.equal(t.pending, 0);
    throttle.offer(10);
    assert.deepEqual(sent, [1, 9, 10], '조용한 방은 바로 반영');
});

test('W = 500: 초당 보고는 최대 1/W(+ 앞쪽 1건)로 묶인다', async () => {
    const t = fakeTimers();
    const { throttle, sent } = server(t, 500);
    // 10ms마다 메시지 한 건씩 5초(500건)
    for (let i = 1; i <= 500; i++) {
        throttle.offer(i);
        await t.advance(10);
    }
    await t.advance(1000);
    assert.ok(sent.length <= 5000 / 500 + 1, `보낸 수 ${sent.length}`);
    assert.equal(sent.at(-1), 500, '마지막 값은 반드시 나간다');
    assert.equal(throttle.acked, 500);
});

test('방을 나가거나 탭이 숨겨지면 창을 기다리지 않고 바로 보낸다', async () => {
    const t = fakeTimers();
    const { throttle, sent } = server(t, 500);
    throttle.offer(1);
    throttle.offer(5);
    await t.advance(100);
    throttle.flush();
    assert.deepEqual(sent, [1, 5]);
    await t.advance(1000);
    assert.deepEqual(sent, [1, 5], '이미 보낸 값은 창 끝에 다시 보내지 않는다');
});

test('429로 거절된 창 끝 보고는 다음 창에 다시 보낸다 — 새 메시지가 없어도 읽음 위치가 멈추지 않는다', async () => {
    const t = fakeTimers();
    const { throttle, sent, setRespond } = server(t, 500);
    throttle.offer(1);
    await flushPromises();
    throttle.offer(7);                // 창 안 → 창 끝에 나갈 값
    setRespond(rejectWith(429));
    await t.advance(500);             // 창 끝: 7 전송 → 429
    assert.deepEqual(sent, [1, 7]);
    assert.equal(throttle.acked, 1);
    assert.equal(throttle.isStuck(), false, '다음 창이 예약돼 있다');
    setRespond(() => Promise.resolve());
    await t.advance(500);             // 다음 창 끝: 다시 7
    assert.deepEqual(sent, [1, 7, 7]);
    assert.equal(throttle.acked, 7);
    await t.advance(2000);
    assert.equal(sent.length, 3, '받아들여진 뒤에는 더 보내지 않는다');
});

test('거절이 계속돼도 재전송은 창 간격으로만 나간다(재시도 폭주 없음)', async () => {
    const t = fakeTimers();
    const { throttle, sent, setRespond } = server(t, 500);
    setRespond(rejectWith(429));
    throttle.offer(3);
    await t.advance(5000);
    assert.ok(sent.length <= 5000 / 500 + 1, `보낸 수 ${sent.length}`);
    assert.ok(sent.every((s) => s === 3));
    assert.equal(throttle.isStuck(), false);
});

test('거절된 값보다 큰 값이 그 사이 생기면 큰 값을 보낸다(최대값 유지)', async () => {
    const t = fakeTimers();
    const { throttle, sent, setRespond } = server(t, 500);
    let reject;
    setRespond(() => new Promise((_, r) => { reject = r; }));
    throttle.offer(4);                 // 바로 나감, 응답 대기
    throttle.offer(6);                 // 창 안
    setRespond(() => Promise.resolve());
    reject(Object.assign(new Error('429'), { status: 429 }));
    await t.advance(500);
    assert.deepEqual(sent, [4, 6]);
    assert.equal(throttle.acked, 6);
});

test('W = 0에서 거절되면 500ms 뒤 다시 보낸다(재전송도 실패하면 다시 500ms 뒤)', async () => {
    const t = fakeTimers();
    const { throttle, sent, setRespond } = server(t, 0);
    setRespond(rejectWith(429));
    throttle.offer(2);
    await flushPromises();
    await t.advance(499);
    assert.deepEqual(sent, [2]);
    await t.advance(1);
    assert.deepEqual(sent, [2, 2]);
    setRespond(() => Promise.resolve());
    await t.advance(500);
    assert.deepEqual(sent, [2, 2, 2]);
    assert.equal(throttle.acked, 2);
    await t.advance(5000);
    assert.equal(sent.length, 3);
});

test('네트워크 실패(상태 없음)·5xx도 다시 보낸다', async () => {
    const t = fakeTimers();
    const { throttle, sent, setRespond } = server(t, 500);
    setRespond(() => Promise.reject(new TypeError('Failed to fetch')));
    throttle.offer(1);
    await flushPromises();
    setRespond(rejectWith(503));
    await t.advance(500);
    setRespond(() => Promise.resolve());
    await t.advance(500);
    assert.deepEqual(sent, [1, 1, 1]);
    assert.equal(throttle.acked, 1);
});

test('400·403은 다시 보내지 않는다', async () => {
    for (const status of [400, 403]) {
        const t = fakeTimers();
        const { throttle, sent, setRespond } = server(t, 500);
        setRespond(rejectWith(status));
        throttle.offer(5);
        await t.advance(3000);
        assert.deepEqual(sent, [5], `${status}`);
        assert.equal(throttle.isStuck(), false, '포기한 값은 멈춘 것으로 보지 않는다');
        setRespond(() => Promise.resolve());
        throttle.offer(6);
        assert.deepEqual(sent, [5, 6], '더 큰 값은 보낸다');
    }
});

test('dispose 뒤에는 보내지 않고 타이머도 남기지 않는다', async () => {
    const t = fakeTimers();
    const { throttle, sent, setRespond } = server(t, 500);
    setRespond(rejectWith(429));
    throttle.offer(1);
    throttle.offer(2);
    throttle.dispose();
    await t.advance(5000);
    assert.deepEqual(sent, [1]);
    assert.equal(t.pending, 0);
});

test('응답 분류', () => {
    assert.equal(classifyReadResult(null), 'ok');
    assert.equal(classifyReadResult({ status: 429 }), 'retry');
    assert.equal(classifyReadResult({ status: 502 }), 'retry');
    assert.equal(classifyReadResult({}), 'retry');
    assert.equal(classifyReadResult({ status: 400 }), 'drop');
    assert.equal(classifyReadResult({ status: 403 }), 'drop');
});
