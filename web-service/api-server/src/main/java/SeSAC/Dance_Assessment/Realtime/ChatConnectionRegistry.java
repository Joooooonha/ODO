package SeSAC.Dance_Assessment.Realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 서버에 붙은 연결들. 서버 메모리에만 있고 언제든 다시 만들어진다(재연결).
 *
 * <ul>
 *   <li>{@code sessionId → 연결} — 나가는 메시지(세션 id만 있다)에서 연결을 찾을 때, 토큰 만료 점검</li>
 *   <li>{@code userId → sessionId들} — 한 사용자의 이 서버 세션 전부(권한·차단 단계에서 쓴다)</li>
 * </ul>
 */
@Component
public class ChatConnectionRegistry {

    private final Map<String, ChatConnection> bySession = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> byUser = new ConcurrentHashMap<>();

    public ChatConnectionRegistry(MeterRegistry registry) {
        Gauge.builder("chat.sessions.active", bySession, Map::size).register(registry);
    }

    void add(ChatConnection connection) {
        bySession.put(connection.sessionId(), connection);
        byUser.computeIfAbsent(connection.userId(), id -> ConcurrentHashMap.newKeySet()).add(connection.sessionId());
    }

    void remove(ChatConnection connection) {
        bySession.remove(connection.sessionId());
        byUser.computeIfPresent(connection.userId(), (id, sessions) -> {
            sessions.remove(connection.sessionId());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public ChatConnection get(String sessionId) {
        return sessionId == null ? null : bySession.get(sessionId);
    }

    public List<ChatConnection> ofUser(long userId) {
        Set<String> sessions = byUser.get(userId);
        if (sessions == null) {
            return List.of();
        }
        return sessions.stream().map(bySession::get).filter(c -> c != null).toList();
    }

    public Collection<ChatConnection> all() {
        return List.copyOf(bySession.values());
    }
}
