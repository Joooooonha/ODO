#!/bin/sh
# 5초마다 이 compose 프로젝트(odo-loadtest)의 모든 컨테이너를 기록한다.
#   $OUT/cgroup.jsonl : {"t", "name", "usage_usec", "nr_periods", "nr_throttled", "throttled_usec", "cpu_max"}
#                       → 단계(시각 구간)마다 앞뒤 차이로 CPU 사용량·throttle 비율을 낸다 (load-test/src/cgroup-report.mjs)
#   $OUT/stats.jsonl  : docker stats --no-stream 한 줄씩(CPU%·메모리)
# Docker Desktop(WSL2)은 cgroupfs + cgroup v2: 컨테이너 cgroup = /sys/fs/cgroup/docker/<전체 id>/
set -u
CG=/host-cgroup/docker
mkdir -p "$OUT"
while :; do
  t=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  docker ps --filter label=com.docker.compose.project=odo-loadtest --no-trunc --format '{{.ID}} {{.Names}}' |
  while read -r id name; do
    f="$CG/$id/cpu.stat"
    [ -r "$f" ] || continue
    # %d는 busybox awk에서 2^31(usage_usec ≈ 2,147 CPU초)에 막혀 오래 떠 있는 postgres가 −2147483648로 찍혔다(10/20 v2 R1) → %.0f
    awk -v t="$t" -v n="$name" -v max="$(cat "$CG/$id/cpu.max")" '
      { v[$1] = $2 }
      END { printf "{\"t\":\"%s\",\"name\":\"%s\",\"usage_usec\":%.0f,\"nr_periods\":%.0f,\"nr_throttled\":%.0f,\"throttled_usec\":%.0f,\"cpu_max\":\"%s\"}\n",
            t, n, v["usage_usec"], v["nr_periods"], v["nr_throttled"], v["throttled_usec"], max }' "$f"
  done >> "$OUT/cgroup.jsonl"
  # 호스트 전체(WSL2 VM, 논리 CPU 전부): /proc/stat의 cpu 줄. 바쁜 시간 = user+nice+system+irq+softirq+steal(USER_HZ 100 → 1틱 10ms).
  # 컨테이너 기록과 같은 모양(name = odo-loadtest-host-1, 상한 없음)이라 단계 정리가 그대로 "host" 사용 코어로 낸다(10/20 측정 환경 v2)
  awk -v t="$t" '/^cpu / { printf "{\"t\":\"%s\",\"name\":\"odo-loadtest-host-1\",\"usage_usec\":%.0f,\"nr_periods\":0,\"nr_throttled\":0,\"throttled_usec\":0,\"cpu_max\":\"max 100000\"}\n", t, ($2+$3+$4+$7+$8+$9) * 10000 }' /proc/stat >> "$OUT/cgroup.jsonl"
  docker stats --no-stream --format "{\"t\":\"$t\",\"name\":\"{{.Name}}\",\"cpu\":\"{{.CPUPerc}}\",\"mem\":\"{{.MemUsage}}\",\"memPerc\":\"{{.MemPerc}}\"}" \
    $(docker ps -q --filter label=com.docker.compose.project=odo-loadtest) >> "$OUT/stats.jsonl" 2>/dev/null
  sleep 5
done
