package SeSAC.Dance_Assessment.Realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 이 서버가 Redis 방 채널({@code chat:room:{id}})을 구독할지(5.3). <b>그 방 구독자가 이 서버에
 * 있을 때만</b> 구독한다.
 *
 * <p><b>"세션 집합 변경 + Redis 구독/해제 결정"을 방 하나의 잠금 아래에서 한 번에 한다.</b>
 * 마지막 구독자 퇴장과 새 구독자 입장이 동시에 일어나도 "집합엔 세션이 있는데 Redis 구독은 끊긴"
 * 상태가 생기지 않는다 — 그 상태의 사용자는 연결이 살아 있는데도 조용히 아무것도 못 받는다.
 *
 * <ul>
 *   <li>숫자가 아니라 세션 id 집합: 탭 여러 개를 따로 세고, 중복 해제가 무해하다</li>
 *   <li>잠금은 방마다 따로 — Redis 명령(최대 명령 시간 제한 500ms)을 기다리는 동안 다른 방은 막지 않는다.
 *       {@code ConcurrentHashMap.compute} 안에서 하면 같은 버킷의 다른 방까지 막힌다</li>
 *   <li>방 항목은 지우지 않는다. 이 서버에서 한 번이라도 쓰인 방 수만큼이라 작다</li>
 *   <li>Redis 명령이 실패해도 리스너 컨테이너는 채널 표를 먼저 갱신하고, 연결이 복구되면 표에 있는
 *       채널을 다시 구독한다(spring-data-redis 소스 확인). 그래서 실패는 기록만 한다</li>
 *   <li>Spring 메모리 브로커의 구독표로 대신하지 않는다 — 갱신 시점을 통제할 수 없다</li>
 * </ul>
 */
@Slf4j
@Component
public class RoomSubscriptions {

    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();
    private final RedisMessageListenerContainer container;
    private final RoomChannelListener listener;

    public RoomSubscriptions(RedisMessageListenerContainer container, RoomChannelListener listener,
                             MeterRegistry registry) {
        this.container = container;
        this.listener = listener;
        Gauge.builder("chat.redis.room.subscriptions", this, RoomSubscriptions::subscribedRoomCount).register(registry);
    }

    public void join(long roomId, String sessionId) {
        Room room = rooms.computeIfAbsent(roomId, id -> new Room());
        room.lock.lock();
        try {
            boolean first = room.sessions.isEmpty();
            if (room.sessions.add(sessionId) && first) {
                room.subscribed = true;
                try {
                    container.addMessageListener(listener, ChatDestinations.roomChannel(roomId));
                    log.debug("[Redis 방 구독] roomId={}", roomId);
                } catch (RuntimeException e) {
                    log.warn("[Redis 방 구독 실패] 연결이 복구되면 컨테이너가 다시 구독한다 roomId={} 원인={}",
                            roomId, e.toString());
                }
            }
        } finally {
            room.lock.unlock();
        }
    }

    public void leave(long roomId, String sessionId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        room.lock.lock();
        try {
            if (room.sessions.remove(sessionId) && room.sessions.isEmpty()) {
                room.subscribed = false;
                try {
                    container.removeMessageListener(listener, ChatDestinations.roomChannel(roomId));
                    log.debug("[Redis 방 구독 해제] roomId={}", roomId);
                } catch (RuntimeException e) {
                    log.warn("[Redis 방 구독 해제 실패] roomId={} 원인={}", roomId, e.toString());
                }
            }
        } finally {
            room.lock.unlock();
        }
    }

    /** 이 서버에서 그 방을 구독 중인 세션들(복사본). */
    public Set<String> sessions(long roomId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return Set.of();
        }
        room.lock.lock();
        try {
            return new HashSet<>(room.sessions);
        } finally {
            room.lock.unlock();
        }
    }

    /** 이 서버가 Redis에 구독을 요청해 둔 방 수. */
    public int subscribedRoomCount() {
        int n = 0;
        for (Room room : rooms.values()) {
            if (room.subscribed) {
                n++;
            }
        }
        return n;
    }

    private static final class Room {
        private final ReentrantLock lock = new ReentrantLock();
        private final Set<String> sessions = new HashSet<>();
        private volatile boolean subscribed;
    }
}
