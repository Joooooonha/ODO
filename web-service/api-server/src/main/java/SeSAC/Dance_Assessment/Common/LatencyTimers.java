package SeSAC.Dance_Assessment.Common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * 측정용 지연 타이머: 평균·최대에 더해 <b>p50·p95·p99</b>를 계산한다(성공 기준을 p95로 정한다).
 *
 * <p>백분위수는 서버 안에서 계산하는 값이라 서버끼리 합칠 수 없다 — 서버마다 따로 본다.
 * 계산 구간은 요약 로그 간격({@code chat.metrics.log-interval})에 맞춘다: 간격을 3칸으로 나눠
 * 굴리므로, 로그 한 줄의 p95는 대략 "최근 한 간격" 동안의 값이다. 측정 때 간격을 10초로 줄이면
 * 백분위수도 10초 구간이 된다. (최대값도 같은 구간을 따른다.)
 */
public final class LatencyTimers {

    public static final double[] PERCENTILES = {0.5, 0.95, 0.99};

    private LatencyTimers() {
    }

    public static Timer.Builder builder(String name, Duration window) {
        return Timer.builder(name)
                .publishPercentiles(PERCENTILES)
                .distributionStatisticExpiry(window)
                .distributionStatisticBufferLength(3);
    }
}
