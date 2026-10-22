import { readFileSync } from 'node:fs';

/**
 * 단계마다 컨테이너별 CPU 사용량·throttle. 사용:
 *   node src/cgroup-report.mjs results/raw/<실행 id>/stages.json results/raw/recorder/cgroup.jsonl
 *
 * recorder(5초 간격)의 누적 cpu.stat에서, 단계 측정 구간 [startedAt, endedAt]을 감싸는 가장 가까운 두 기록의 차이를 쓴다
 * (구간이 최대 5초씩 넓어진다). throttle 비율 = nr_throttled / nr_periods. 결과는 JSON으로 표준 출력.
 */
const [stagesFile, cgroupFile] = process.argv.slice(2);
const stages = JSON.parse(readFileSync(stagesFile, 'utf8'));
const byName = new Map();
for (const line of readFileSync(cgroupFile, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const row = JSON.parse(line);
  row.ms = Date.parse(row.t);
  if (!byName.has(row.name)) byName.set(row.name, []);
  byName.get(row.name).push(row);
}

const out = stages.map((s) => {
  const from = Date.parse(s.startedAt);
  const to = Date.parse(s.endedAt);
  const containers = {};
  for (const [name, rows] of byName) {
    const a = rows.filter((r) => r.ms <= from).at(-1);
    const b = rows.find((r) => r.ms >= to);
    if (!a || !b || b.usage_usec < a.usage_usec) continue;
    const sec = (b.ms - a.ms) / 1000;
    const [quota, period] = b.cpu_max.split(' ');
    const periods = b.nr_periods - a.nr_periods;
    const throttled = b.nr_throttled - a.nr_throttled;
    containers[name] = {
      cpusLimit: quota === 'max' ? null : Number(quota) / Number(period),
      cpusUsed: Math.round(((b.usage_usec - a.usage_usec) / 1e6 / sec) * 100) / 100,
      nrPeriods: periods,
      nrThrottled: throttled,
      throttledRatio: periods ? Math.round((throttled / periods) * 1000) / 1000 : 0,
      throttledMs: Math.round((b.throttled_usec - a.throttled_usec) / 1000),
      windowSec: sec,
    };
  }
  return { r: s.r, startedAt: s.startedAt, endedAt: s.endedAt, containers };
});
process.stdout.write(JSON.stringify(out, null, 2) + '\n');
