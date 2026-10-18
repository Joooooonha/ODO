import hdr from 'hdr-histogram-js';

/**
 * 지연 히스토그램(HdrHistogram, 정확한 구현). 단위는 **마이크로초**. 1µs ~ 60초, 유효 숫자 3자리(오차 0.1% 이내).
 * 작업 프로세스마다 기록하고, 조정자가 압축 문자열로 받아 합친다(히스토그램은 합칠 수 있다 — 백분위수는 못 합친다).
 */
const OPTIONS = { lowestDiscernibleValue: 1, highestTrackableValue: 60_000_000, numberOfSignificantValueDigits: 3 };

export const createHistogram = () => hdr.build(OPTIONS);

/** ns(bigint) 차이를 µs로 기록. 음수(시계 문제)는 따로 센다. */
export function recordNs(h, diffNs, onNegative) {
  if (diffNs < 0n) {
    onNegative?.();
    return;
  }
  const us = Number(diffNs / 1000n);
  h.recordValue(Math.min(Math.max(us, 1), OPTIONS.highestTrackableValue));
}

export const encode = (h) => hdr.encodeIntoCompressedBase64(h);
export const decode = (s) => hdr.decodeFromCompressedBase64(s);

export function merge(encodedList) {
  const total = createHistogram();
  for (const s of encodedList) {
    if (!s) continue;
    const h = decode(s);
    total.add(h);
    h.destroy?.();
  }
  return total;
}

/** {count, p50, p95, p99, max} — ms(소수 둘째 자리) */
export function summarize(h) {
  const ms = (us) => Math.round(us / 10) / 100;
  if (h.totalCount === 0) return { count: 0, p50: null, p95: null, p99: null, max: null };
  return {
    count: h.totalCount,
    p50: ms(h.getValueAtPercentile(50)),
    p95: ms(h.getValueAtPercentile(95)),
    p99: ms(h.getValueAtPercentile(99)),
    max: ms(h.maxValue),
  };
}
