package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Common.LatencyTimers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 실시간 전달 지표(10절, 4.5 측정 준비).
 *
 * <ul>
 *   <li>{@code chat.session.queue.wait} — SEND가 세션 대기열에서 기다린 시간(도착 → 처리 시작), p50·p95·p99</li>
 *   <li>{@code chat.session.disconnects{reason}} — 서버가 끊은 세션.
 *       {@code heartbeat}(하트비트 무신호 30초), {@code queue_limit}(대기열 상한 초과),
 *       {@code token_expired}, {@code protocol}(한 메시지에 프레임 여러 개 등)</li>
 *   <li>{@code chat.redis.publish.failures} — Redis 발행 실패(저장은 이미 끝났다)</li>
 *   <li>{@code chat.subscribe.denied} — 권한 없는 방 구독 시도</li>
 * </ul>
 */
@Component
public class ChatRealtimeMetrics {

    public static final String HEARTBEAT = "heartbeat";
    public static final String QUEUE_LIMIT = "queue_limit";
    public static final String TOKEN_EXPIRED = "token_expired";
    public static final String PROTOCOL = "protocol";

    private final MeterRegistry registry;
    private final Timer queueWait;

    public ChatRealtimeMetrics(MeterRegistry registry,
                               @Value("${chat.metrics.log-interval:60s}") Duration percentileWindow) {
        this.registry = registry;
        this.queueWait = LatencyTimers.builder("chat.session.queue.wait", percentileWindow).register(registry);
    }

    void queueWait(long nanos) {
        if (nanos >= 0) {
            queueWait.record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    void disconnected(String reason) {
        registry.counter("chat.session.disconnects", "reason", reason).increment();
    }

    void publishFailed() {
        registry.counter("chat.redis.publish.failures").increment();
    }

    void subscribeDenied() {
        registry.counter("chat.subscribe.denied").increment();
    }

    public long disconnects(String reason) {
        Counter c = registry.find("chat.session.disconnects").tag("reason", reason).counter();
        return c == null ? 0 : (long) c.count();
    }

    public long publishFailures() {
        Counter c = registry.find("chat.redis.publish.failures").counter();
        return c == null ? 0 : (long) c.count();
    }

    public Timer queueWaitTimer() {
        return queueWait;
    }
}
