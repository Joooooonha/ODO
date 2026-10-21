package SeSAC.Dance_Assessment.Realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * STOMP 처리 바로 바깥의 웹소켓 단계. <b>세션 대기열에 들어가기 전에 SEND를 센다(4.4).</b>
 *
 * <p>Spring의 순서 보존 대기열은 크기를 알려주지 않고, 채널 인터셉터는 메시지를 대기열에서
 * <b>꺼낼 때</b> 불린다(spring-messaging {@code OrderedMessageChannelDecorator} 확인). 대기열에 넣기
 * 전에 끼어들 수 있는 곳은 STOMP 해석 전인 여기뿐이다. 처리 시작 때 빼는 것은
 * {@link ChatInboundInterceptor}다.
 *
 * <ul>
 *   <li>웹소켓 메시지 하나에 STOMP 프레임 하나만 받는다(끝에 NUL 하나, 그 뒤엔 줄바꿈만). 브라우저
 *       클라이언트는 프레임마다 따로 보낸다. 여러 프레임을 한 메시지에 담으면 상한을 피해 갈 수 있어서
 *       프로토콜 위반으로 끊는다</li>
 *   <li>SEND만 센다. 재연결 직후 방 수만큼 오는 SUBSCRIBE까지 세면 방이 많은 정상 사용자가 끊긴다</li>
 *   <li>상한을 넘으면 그 메시지는 넘기지 않고 ERROR {@code QUEUE_LIMIT_EXCEEDED} 후 연결을 끊는다.
 *       전송 창(50)을 지키는 정상 브라우저는 닿지 않는다(재개 순간 옛 사본 최대 49 + 새 사본 50)</li>
 * </ul>
 */
@Slf4j
public class ChatWebSocketHandler extends WebSocketHandlerDecorator {

    private final ChatConnectionRegistry registry;
    private final ConnectionCloser closer;
    private final int queueLimit;

    public ChatWebSocketHandler(WebSocketHandler delegate, ChatConnectionRegistry registry,
                                ConnectionCloser closer, int queueLimit) {
        super(delegate);
        this.registry = registry;
        this.closer = closer;
        this.queueLimit = queueLimit;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ChatConnection connection = connectionOf(session);
        if (connection == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        connection.bind(session);
        registry.add(connection);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        ChatConnection connection = connectionOf(session);
        if (connection == null || connection.isClosed()) {
            // 서버가 끊기로 한 뒤 실제로 닫히기 전에 도착한 것. 세지도, 다시 끊지도 않는다.
            return;
        }
        if (message instanceof BinaryMessage) {
            closer.closeForProtocol(connection, "binary");
            return;
        }
        if (message instanceof TextMessage text) {
            String payload = text.getPayload();
            if (!isHeartbeat(payload)) {
                if (!isSingleFrame(payload)) {
                    closer.closeForProtocol(connection, "frames-per-message");
                    return;
                }
                if ("SEND".equals(command(payload))) {
                    int pending = connection.sendArrived();
                    if (pending > queueLimit) {
                        closer.closeWithError(connection, ConnectionCloser.QUEUE_LIMIT_EXCEEDED,
                                ChatRealtimeMetrics.QUEUE_LIMIT);
                        return;
                    }
                }
            }
        }
        super.handleMessage(session, message);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        try {
            super.afterConnectionClosed(session, closeStatus);
        } finally {
            ChatConnection connection = connectionOf(session);
            if (connection != null) {
                registry.remove(connection);
            }
        }
    }

    private static ChatConnection connectionOf(WebSocketSession session) {
        return (ChatConnection) session.getAttributes().get(ChatConnection.ATTRIBUTE);
    }

    /** 줄바꿈만 있는 메시지(STOMP 하트비트). */
    static boolean isHeartbeat(String payload) {
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** NUL이 정확히 하나이고, 그 뒤에는 줄바꿈만 있다. */
    static boolean isSingleFrame(String payload) {
        int nul = payload.indexOf('\0');
        if (nul < 0 || payload.indexOf('\0', nul + 1) >= 0) {
            return false;
        }
        return isHeartbeat(payload.substring(nul + 1));
    }

    /** 앞의 줄바꿈을 건너뛴 첫 줄. */
    static String command(String payload) {
        int start = 0;
        while (start < payload.length() && (payload.charAt(start) == '\n' || payload.charAt(start) == '\r')) {
            start++;
        }
        int end = payload.indexOf('\n', start);
        String line = end < 0 ? payload.substring(start) : payload.substring(start, end);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
