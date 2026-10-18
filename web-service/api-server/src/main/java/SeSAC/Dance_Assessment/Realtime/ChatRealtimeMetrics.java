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
 *   <li>{@code chat.control.published{type}}, {@code chat.control.received{type}},
 *       {@code chat.control.publish.failures} — 제어 이벤트(REVOKED·GRANTED)</li>
 *   <li>{@code chat.access.recheck.corrections{type}} — <b>5초 재확인이 이벤트보다 먼저 발견한 변경</b>
 *       (= 이벤트가 유실됐거나 늦은 수). 정상이면 0에 가깝다</li>
 *   <li>{@code chat.outbound.dropped} — 허용 방 캐시에 없어 나가기 직전에 버린 방 메시지</li>
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

    void controlPublished(String type) {
        registry.counter("chat.control.published", "type", type).increment();
    }

    void controlPublishFailed() {
        registry.counter("chat.control.publish.failures").increment();
    }

    void controlReceived(String type) {
        registry.counter("chat.control.received", "type", type).increment();
    }

    void recheckCorrected(String type, int sessions) {
        if (sessions > 0) {
            registry.counter("chat.access.recheck.corrections", "type", type).increment(sessions);
        }
    }

    void outboundDropped() {
        registry.counter("chat.outbound.dropped").increment();
    }

    public long recheckCorrections(String type) {
        Counter c = registry.find("chat.access.recheck.corrections").tag("type", type).counter();
        return c == null ? 0 : (long) c.count();
    }

    public long outboundDroppedCount() {
        Counter c = registry.find("chat.outbound.dropped").counter();
        return c == null ? 0 : (long) c.count();
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
