package SeSAC.Dance_Assessment.Common;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadCpuMetricsTest {

    @Test
    @DisplayName("스레드 이름 접두어로 풀을 나눈다")
    void classifiesByThreadName() {
        assertThat(ThreadCpuMetrics.poolOf("clientInboundChannel-3")).isEqualTo("inbound");
        assertThat(ThreadCpuMetrics.poolOf("clientOutboundChannel-12")).isEqualTo("outbound");
        assertThat(ThreadCpuMetrics.poolOf("http-nio-8080-exec-187")).isEqualTo("tomcat");
        assertThat(ThreadCpuMetrics.poolOf("http-nio-8080-Poller")).isEqualTo("other");
        assertThat(ThreadCpuMetrics.poolOf("redis-dispatch-1")).isEqualTo("redis");
        assertThat(ThreadCpuMetrics.poolOf("C2 CompilerThread0")).isEqualTo("other");
        assertThat(ThreadCpuMetrics.poolOf(null)).isEqualTo("other");
    }

    @Test
    @DisplayName("이 스레드가 CPU를 쓰면 other 풀의 누적 초가 늘고, 줄지 않는다")
    void accumulatesMonotonically() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadCpuMetrics metrics = new ThreadCpuMetrics();
        metrics.bindTo(registry);
        double before = registry.get("chat.threads.cpu").tag("pool", "other").functionCounter().count();
        long end = System.nanoTime() + 700_000_000L;
        long x = 0;
        while (System.nanoTime() < end) {
            x += System.nanoTime() % 7;
        }
        assertThat(x).isPositive();
        double after = registry.get("chat.threads.cpu").tag("pool", "other").functionCounter().count();
        assertThat(after).isGreaterThan(before);
        assertThat(ThreadCpuMetrics.POOLS).hasSize(5);
    }
}
