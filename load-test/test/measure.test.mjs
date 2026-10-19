import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createHistogram, encode, merge, recordNs, summarize } from '../src/hist.mjs';
import { buildTopology, workerOfTeam } from '../src/topology.mjs';
import { signToken } from '../src/jwt.mjs';

test('히스토그램: 알려진 값의 백분위수가 0.1% 안에서 나온다', () => {
  const h = createHistogram();
  for (let ms = 1; ms <= 1000; ms++) recordNs(h, BigInt(ms) * 1_000_000n);   // 1~1000ms 균등
  const s = summarize(h);
  assert.equal(s.count, 1000);
  for (const [got, want] of [[s.p50, 500], [s.p95, 950], [s.p99, 990], [s.max, 1000]]) {
    assert.ok(Math.abs(got - want) / want <= 0.001, `${got} vs ${want}`);
  }
});

test('히스토그램: 합치면 두 프로세스의 기록을 모두 가진다(백분위수는 합친 뒤 계산)', () => {
  const a = createHistogram();
  const b = createHistogram();
  for (let i = 0; i < 990; i++) recordNs(a, 10_000_000n);    // 10ms × 990
  for (let i = 0; i < 10; i++) recordNs(b, 500_000_000n);    // 500ms × 10
  const s = summarize(merge([encode(a), encode(b)]));
  assert.equal(s.count, 1000);
  assert.ok(Math.abs(s.p50 - 10) < 0.02);
  assert.ok(Math.abs(s.max - 500) < 0.5);
});

test('히스토그램: 음수(시계 문제)는 기록하지 않고 센다', () => {
  const h = createHistogram();
  let negatives = 0;
  recordNs(h, -5n, () => negatives++);
  assert.equal(h.totalCount, 0);
  assert.equal(negatives, 1);
});

test('배치: 1인 1팀 — 모든 사용자가 정확히 한 팀, 팀마다 N명', () => {
  const t = buildTopology({ rooms: 10, members: 20, watchersPerRoom: 5 });
  assert.equal(t.userCount, 200);
  t.userRooms.forEach((rooms) => assert.equal(rooms.length, 1));
  t.teams.forEach((team) => {
    assert.equal(new Set(team.members).size, 20);
    assert.equal(team.watchers.length, 5);
  });
});

test('배치: 1인 2팀 — 모든 사용자가 정확히 두 팀, 팀마다 N명, 주 팀이 자기 블록', () => {
  const t = buildTopology({ rooms: 10, members: 20, teamsPerUser: 2 });
  assert.equal(t.userCount, 100);
  t.userRooms.forEach((rooms) => assert.equal(new Set(rooms).size, 2));
  t.teams.forEach((team) => assert.equal(new Set(team.members).size, 20));
  for (let u = 0; u < t.userCount; u++) assert.ok(t.userRooms[u].includes(t.teams[t.primaryTeam(u)].roomId));
});

test('배치: 같은 방의 세션은 같은 작업 프로세스(1인 1팀), DM 두 사람도 같은 프로세스', () => {
  const t = buildTopology({ rooms: 30, members: 20, dmPairsPerRoom: 2 });
  for (const team of t.teams) {
    const workers = new Set(team.members.map((u) => workerOfTeam(t.primaryTeam(u), t.rooms, 6)));
    assert.equal(workers.size, 1);
  }
  for (const dm of t.dms) {
    assert.equal(workerOfTeam(t.primaryTeam(dm.a), t.rooms, 6), workerOfTeam(t.primaryTeam(dm.b), t.rooms, 6));
  }
});

test('배치: 활발한 방이 작업 프로세스마다 고르게 나뉜다(한 프로세스에 몰리지 않게)', () => {
  const t = buildTopology({ rooms: 100, members: 20, activeRoomRatio: 0.1 });
  const per = new Map();
  t.teams.filter((x) => x.active).forEach((x) => {
    const w = workerOfTeam(x.index, t.rooms, 6);
    per.set(w, (per.get(w) ?? 0) + 1);
  });
  assert.equal(per.size, 6);
});

test('JWT: HS256 서명이 같은 키로 검증된다, 32바이트 미만 키는 거부', () => {
  const secret = 'test-only-secret-0123456789abcdef0123';
  const token = signToken(secret, 1000001);
  const [h, p, sig] = token.split('.');
  assert.equal(createHmac('sha256', secret).update(`${h}.${p}`).digest('base64url'), sig);
  assert.equal(JSON.parse(Buffer.from(p, 'base64url')).sub, '1000001');
  assert.throws(() => signToken('short', 1));
});

test('읽음 보고 묶기: 생성기 세션이 화면과 같은 파일(readThrottle.js)을 가져온다', async () => {
  const { readFileSync } = await import('node:fs');
  const src = readFileSync(new URL('../src/session.mjs', import.meta.url), 'utf8');
  assert.match(src, /from '\.\.\/\.\.\/web-service\/frontend\/src\/chat\/readThrottle\.js'/);
  const { ReadThrottle } = await import('../../web-service/frontend/src/chat/readThrottle.js');
  const sent = [];
  const t = new ReadThrottle({ windowMs: 0, send: (s) => { sent.push(s); return Promise.resolve(); } });
  t.offer(3);
  assert.deepEqual(sent, [3]);
});
