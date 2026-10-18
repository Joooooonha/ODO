package SeSAC.Dance_Assessment.Service.Chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * 메시지 저장 지표(10절, 4.5 측정 준비). Micrometer에 기록한다 — 서버 메모리에만 있고 재시작하면
 * 0부터 다시 센다. 판단 근거용이지 정확성에 쓰지 않는다.
 *
 * <ul>
 *   <li>{@code chat.send.duration{outcome}} — 한 메시지의 저장 처리 시간(재시도·대기 포함)</li>
 *   <li>{@code chat.send.retries{cause}} — 재시도 횟수(원인: SQLState 또는 예외 이름).
 *       교착({@code 40P01})은 잠금 순서를 지키면 0이어야 한다</li>
 *   <li>{@code chat.send.exhausted}, {@code chat.send.unclassified}, {@code chat.send.duplicates},
 *       {@code chat.send.invalid}</li>
 * </ul>
 */
@Component
public class ChatSendMetrics {

    private final MeterRegistry registry;

    @Autowired
    public ChatSendMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 단위 테스트용. */
    ChatSendMetrics() {
        this(new SimpleMeterRegistry());
    }

    void retried(Throwable cause) {
        registry.counter("chat.send.retries", "cause", causeKey(cause)).increment();
    }

    void exhausted() {
        registry.counter("chat.send.exhausted").increment();
    }

    void unclassified() {
        registry.counter("chat.send.unclassified").increment();
    }

    void duplicate() {
        registry.counter("chat.send.duplicates").increment();
    }

    void invalid() {
        registry.counter("chat.send.invalid").increment();
    }

    void recordDuration(SendOutcome outcome, Duration elapsed) {
        Timer.builder("chat.send.duration")
                .tag("outcome", outcome.getClass().getSimpleName())
                .register(registry)
                .record(elapsed);
    }

    public long retries(String cause) {
        Counter c = registry.find("chat.send.retries").tag("cause", cause).counter();
        return c == null ? 0 : (long) c.count();
    }

    public Map<String, Long> retriesByCause() {
        Map<String, Long> out = new TreeMap<>();
        registry.find("chat.send.retries").counters()
                .forEach(c -> out.put(c.getId().getTag("cause"), (long) c.count()));
        return out;
    }

    public long exhaustedCount() {
        return count("chat.send.exhausted");
    }

    public long unclassifiedCount() {
        return count("chat.send.unclassified");
    }

    public long duplicateCount() {
        return count("chat.send.duplicates");
    }

    public long invalidCount() {
        return count("chat.send.invalid");
    }

    private long count(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0 : (long) c.count();
    }

    /** 원인 사슬에서 SQLState를 찾으면 그것을, 없으면 가장 바깥 예외 이름을 쓴다. */
    static String causeKey(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return failure == null ? "-" : failure.getClass().getSimpleName();
    }
}
