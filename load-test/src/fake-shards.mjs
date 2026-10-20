import { fork } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { merge, summarize } from './hist.mjs';

/**
 * 가짜 서버를 프로세스 여러 개로 나눈다(보정 범위 확장, 10/20 작업 1). 사용:
 *   node src/fake-shards.mjs --port 18080 --procs 4
 *
 * 가짜 서버 하나는 이벤트 루프 하나라, W=500 (나) 보정의 r = 2,000에서 컨테이너 CPU 4개 중 1.4~1.6만 쓰고
 * throttle 0인 채로 처리가 6초까지 밀렸다(루프 하나가 포화). 그래서 샤드 N개(포트 port+1 … port+N)로 나누고,
 * 생성기 작업 프로세스 w는 샤드 (w mod N)에 붙는다. 방은 작업 프로세스별로 나뉘어 있으므로(topology.workerOfTeam,
 * 1인 1팀이고 DM은 같은 블록 안) **샤드끼리 같은 방을 나누지 않는다** — 방송이 샤드를 넘을 일이 없다.
 *
 * 이 프로세스(port)는 조정만 한다: /fake/configure·/fake/reset은 모든 샤드에 그대로 보내고, /fake/stats는 샤드의
 * 히스토그램(압축 문자열)을 합쳐 하나의 가짜 서버처럼 답한다. /fake/shards는 샤드 주소 목록이다.
 */
const here = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]]);
  return acc;
}, []));
const port = Number(args.port ?? 18080);
const procs = Number(args.procs ?? 4);
const host = args.host ?? 'fake-server';

const ports = Array.from({ length: procs }, (_, i) => port + 1 + i);
const passArgs = args.deflate ? ['--deflate', args.deflate] : [];
await Promise.all(ports.map((p) => new Promise((resolve) => {
  const child = fork(path.join(here, 'fake-server.mjs'), ['--port', String(p), ...passArgs], { stdio: ['ignore', 'pipe', 'inherit', 'ipc'] });
  child.stdout.on('data', (d) => {
    process.stdout.write(d);
    if (d.toString().includes(`[가짜 서버] :${p}`)) resolve();
  });
  child.on('exit', (code) => {
    console.error(`[가짜 서버 샤드] :${p} 종료 code=${code}`);
    process.exit(1);
  });
})));

const each = (pathname, init) => Promise.all(ports.map((p) => fetch(`http://127.0.0.1:${p}${pathname}`, init)));

const KINDS = ['injected', 'timerLate', 'serverSpan'];
async function mergedStats() {
  const parts = await Promise.all((await each('/fake/stats?raw=1')).map((r) => r.json()));
  const sum = (k) => parts.reduce((a, s) => a + (s[k] ?? 0), 0);
  const out = Object.fromEntries(KINDS.map((k) => [k, summarize(merge(parts.map((s) => s.raw[k])))]));
  out.spanByKind = Object.fromEntries(['MESSAGE', 'ACK', 'READ'].map((k) => [k, summarize(merge(parts.map((s) => s.raw.spanByKind[k])))]));
  for (const k of ['sent', 'readPosts', 'readRejected', 'readBroadcasts', 'readSkipped', 'pendingTimers', 'framesOut']) out[k] = sum(k);
  out.shards = parts.map((s, i) => ({ port: ports[i], sent: s.sent, framesOut: s.framesOut, serverSpanP99: s.serverSpan.p99 }));
  return out;
}

http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', async () => {
    const url = new URL(req.url, 'http://x');
    const send = (status, payload) => {
      res.writeHead(status, payload == null ? {} : { 'Content-Type': 'application/json' });
      res.end(payload == null ? undefined : JSON.stringify(payload));
    };
    try {
      if (url.pathname === '/fake/shards') {
        return send(200, ports.map((p) => ({ httpUrl: `http://${host}:${p}`, wsUrl: `ws://${host}:${p}/api/ws` })));
      }
      if (url.pathname === '/fake/stats') return send(200, await mergedStats());
      if (url.pathname === '/fake/reset' || url.pathname === '/fake/configure') {
        await each(url.pathname, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body || undefined });
        return send(204);
      }
      return send(404, { code: 'NOT_FOUND', hint: '측정 트래픽은 /fake/shards의 샤드로 보낸다' });
    } catch (e) {
      return send(500, { code: 'SHARD_ERROR', message: String(e) });
    }
  });
}).listen(port, () => console.log(`[가짜 서버] :${port} 조정 프로세스, 샤드 ${procs}개 :${ports.join(',')}`));
