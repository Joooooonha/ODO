package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ChatSendSession;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 웹소켓 연결 하나의 서버 쪽 상태. 핸드셰이크 때 만들어 세션 속성 맵에 넣는다.
 *
 * <p><b>세션 속성 맵은 그 연결의 모든 메시지가 같은 객체를 들고 다닌다</b>(Spring 소스 확인:
 * {@code AbstractWebSocketSession}의 맵 하나를 {@code StompSubProtocolHandler}가 메시지마다,
 * 그리고 연결 종료 메시지에도 그대로 넣는다). 그래서 연결이 끊긴 뒤 대기열에서 나오는 메시지도
 * 이 객체의 {@code closed}를 본다.
 *
 * <p>구독 표({@code subscriptionId → roomId})와 {@code closed}는 이 객체의 잠금 아래에서 함께
 * 바꾼다 — 끊긴 뒤 대기열에서 늦게 나온 SUBSCRIBE가 정리 뒤에 방 구독을 되살리지 못하게 한다.
 * 잠금 순서: 이 객체 → 방({@link RoomSubscriptions}).
 */
public class ChatConnection {

    public static final String ATTRIBUTE = "odo.chat.connection";

    private final long userId;
    private final Instant tokenExpiresAt;
    private final ChatSendSession sendState = new ChatSendSession();

    /** 도착했지만 아직 처리를 시작하지 않은 SEND 수(4.4). */
    private final AtomicInteger pendingSends = new AtomicInteger();
    /** 도착 시각(nanoTime). 대기열이 FIFO라 처리 시작 때 앞에서 하나씩 꺼내면 대기 시간이 된다. */
    private final ConcurrentLinkedQueue<Long> arrivals = new ConcurrentLinkedQueue<>();

    private final Map<String, Long> subscriptions = new HashMap<>();

    private volatile String sessionId;
    private volatile WebSocketSession webSocket;

    public ChatConnection(long userId, Instant tokenExpiresAt) {
        this.userId = userId;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    void bind(WebSocketSession session) {
        this.sessionId = session.getId();
        this.webSocket = session;
    }

    public long userId() {
        return userId;
    }

    /** STOMP 사용자 이름(Principal.getName()). 개인 목적지를 고를 때 쓴다. */
    public String userName() {
        return String.valueOf(userId);
    }

    public Instant tokenExpiresAt() {
        return tokenExpiresAt;
    }

    public ChatSendSession sendState() {
        return sendState;
    }

    public String sessionId() {
        return sessionId;
    }

    WebSocketSession webSocket() {
        return webSocket;
    }

    public boolean isClosed() {
        return sendState.isClosed();
    }

    // ===== 대기열 수 세기 (4.4) =====

    /** @return 도착 처리 후 대기 중인 SEND 수 */
    int sendArrived() {
        arrivals.add(System.nanoTime());
        return pendingSends.incrementAndGet();
    }

    /** @return 도착부터 처리 시작까지 걸린 나노초. 도착 기록이 없으면 -1 */
    long sendStarted() {
        pendingSends.decrementAndGet();
        Long arrivedAt = arrivals.poll();
        return arrivedAt == null ? -1 : System.nanoTime() - arrivedAt;
    }

    int pendingSends() {
        return pendingSends.get();
    }

    // ===== 구독 표 (이 객체의 잠금 아래에서만) =====

    /** @return 이미 끊긴 연결이면 false(구독하지 않는다) */
    synchronized boolean addSubscription(String subscriptionId, long roomId, RoomSubscriptions rooms) {
        if (isClosed()) {
            return false;
        }
        Long previous = subscriptions.put(subscriptionId, roomId);
        if (previous != null && previous != roomId) {
            leaveIfLast(previous, rooms);
        }
        rooms.join(roomId, sessionId);
        return true;
    }

    synchronized void removeSubscription(String subscriptionId, RoomSubscriptions rooms) {
        Long roomId = subscriptions.remove(subscriptionId);
        if (roomId != null) {
            leaveIfLast(roomId, rooms);
        }
    }

    /** 연결 종료. 되돌릴 수 없다. 여러 번 불려도 결과가 같다. */
    synchronized void close(RoomSubscriptions rooms) {
        sendState.close();
        Set<Long> joined = new HashSet<>(subscriptions.values());
        subscriptions.clear();
        joined.forEach(roomId -> rooms.leave(roomId, sessionId));
    }

    synchronized Set<Long> subscribedRooms() {
        return new HashSet<>(subscriptions.values());
    }

    private void leaveIfLast(long roomId, RoomSubscriptions rooms) {
        if (!subscriptions.containsValue(roomId)) {
            rooms.leave(roomId, sessionId);
        }
    }
}
