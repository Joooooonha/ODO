package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Common.LatencyTimers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 읽음 지표(Task-chat-read 5절).
 *
 * <ul>
 *   <li>{@code chat.read.requests{outcome}} — 읽음 보고 수와 결과.
 *       {@code advanced}(위치가 올라감 → 스냅샷·발행) / {@code noop}(같거나 작은 값) / {@code forbidden}(멤버 아님) /
 *       {@code invalid}(seq 없음·음수·마지막 번호보다 큼) / {@code rejected}(읽음 벌크헤드가 거절 → 429, DB를 쓰기 전)</li>
 *   <li>{@code chat.read.duration{outcome}} — 읽음 보고 처리 시간(갱신 + 스냅샷 + 발행), p50·p95·p99</li>
 *   <li>{@code chat.read.snapshot.duration}, {@code chat.read.snapshot.rows} — 스냅샷 조회 시간·행 수
 *       (읽음 보고와 read-state 조회 모두)</li>
 * </ul>
 */
@Component
public class ChatReadMetrics {

    public static final String ADVANCED = "advanced";
    public static final String NOOP = "noop";
    public static final String FORBIDDEN = "forbidden";
    public static final String INVALID = "invalid";
    public static final String REJECTED = "rejected";

    private final MeterRegistry registry;
    private final Duration window;
    private final Map<String, Timer> durations = new ConcurrentHashMap<>();
    private final Timer snapshotDuration;
    private final DistributionSummary snapshotRows;

    public ChatReadMetrics(MeterRegistry registry, @Value("${chat.metrics.log-interval:60s}") Duration window) {
        this.registry = registry;
        this.window = window;
        this.snapshotDuration = LatencyTimers.builder("chat.read.snapshot.duration", window).register(registry);
        this.snapshotRows = DistributionSummary.builder("chat.read.snapshot.rows").register(registry);
    }

    void request(String outcome, long nanos) {
        registry.counter("chat.read.requests", "outcome", outcome).increment();
        durations.computeIfAbsent(outcome, o -> LatencyTimers.builder("chat.read.duration", window)
                .tag("outcome", o).register(registry)).record(nanos, TimeUnit.NANOSECONDS);
    }

    void snapshot(long nanos, int rows) {
        snapshotDuration.record(nanos, TimeUnit.NANOSECONDS);
        snapshotRows.record(rows);
    }

    public long requests(String outcome) {
        Counter c = registry.find("chat.read.requests").tag("outcome", outcome).counter();
        return c == null ? 0 : (long) c.count();
    }
}
