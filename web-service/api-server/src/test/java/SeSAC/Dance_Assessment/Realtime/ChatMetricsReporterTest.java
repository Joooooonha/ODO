package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Common.LatencyTimers;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 측정용 지연 지표의 백분위수와 요약 로그 형식. */
class ChatMetricsReporterTest {

    @Test
    @DisplayName("저장 처리 시간·대기열 대기 시간은 p50·p95·p99를 계산하고 요약 로그에 나온다")
    void percentilesInSummary() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer send = LatencyTimers.builder("chat.send.duration", Duration.ofSeconds(10))
                .tag("outcome", "Saved").register(registry);
        ChatRealtimeMetrics realtime = new ChatRealtimeMetrics(registry, Duration.ofSeconds(10));
        for (int ms = 1; ms <= 100; ms++) {
            send.record(ms, TimeUnit.MILLISECONDS);
            realtime.queueWait(TimeUnit.MILLISECONDS.toNanos(ms));
        }

        double p95 = Arrays.stream(send.takeSnapshot().percentileValues())
                .filter(v -> v.percentile() == 0.95).mapToDouble(v -> v.value(TimeUnit.MILLISECONDS)).findFirst().orElseThrow();
        assertThat(p95).as("1~100ms 중 p95").isCloseTo(95, within(5.0));
        assertThat(realtime.queueWaitTimer().takeSnapshot().percentileValues())
                .extracting(ValueAtPercentile::percentile).containsExactly(0.5, 0.95, 0.99);

        String summary = new ChatMetricsReporter(registry).summary();
        assertThat(summary).contains("Saved=100건 평균50.5/p50 ").contains("/p95 ").contains("/p99 ").contains("/최대100.0ms");
        assertThat(summary).contains("대기열대기 100건 평균50.5/p50 ");
    }
}
