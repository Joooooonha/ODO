package SeSAC.Dance_Assessment.Service.Chat;

import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 메시지 저장 지표(10절). 서버 메모리에만 있고 재시작하면 0부터 다시 센다 — 판단 근거용이지
 * 정확성에 쓰지 않는다.
 *
 * <ul>
 *   <li>재시도 횟수(원인별: SQLState 또는 예외 이름). 교착({@code 40P01})은 잠금 순서를 지키면 0이어야 한다</li>
 *   <li>재시도 소진, 분류되지 않은 예외, 중복 메시지(재전송 빈도), 입력 오류(길이 초과 포함)</li>
 * </ul>
 */
@Component
public class ChatSendMetrics {

    private final Map<String, AtomicLong> retriesByCause = new ConcurrentHashMap<>();
    private final AtomicLong exhausted = new AtomicLong();
    private final AtomicLong unclassified = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();

    void retried(Throwable cause) {
        retriesByCause.computeIfAbsent(causeKey(cause), k -> new AtomicLong()).incrementAndGet();
    }

    void exhausted() {
        exhausted.incrementAndGet();
    }

    void unclassified() {
        unclassified.incrementAndGet();
    }

    void duplicate() {
        duplicates.incrementAndGet();
    }

    void invalid() {
        invalid.incrementAndGet();
    }

    public long retries(String cause) {
        AtomicLong n = retriesByCause.get(cause);
        return n == null ? 0 : n.get();
    }

    public Map<String, Long> retriesByCause() {
        Map<String, Long> copy = new TreeMap<>();
        retriesByCause.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }

    public long exhaustedCount() {
        return exhausted.get();
    }

    public long unclassifiedCount() {
        return unclassified.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long invalidCount() {
        return invalid.get();
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
