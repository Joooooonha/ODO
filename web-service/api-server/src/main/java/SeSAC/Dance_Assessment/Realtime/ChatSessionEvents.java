package SeSAC.Dance_Assessment.Realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * 연결 종료: 그 연결에 {@code closed}를 세우고 방 구독표에서 모두 뺀다(4.3, 5.3).
 *
 * <p>같은 세션에 여러 번 불릴 수 있어서(Spring 문서) 몇 번 불려도 결과가 같게 한다. 이벤트는
 * 웹소켓 스레드에서 동기로 불리고, 대기열에 남은 메시지는 그 뒤에 처리되더라도 같은 연결 객체를
 * 보므로 버려진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionEvents {

    private final RoomSubscriptions rooms;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(event.getMessage().getHeaders());
        ChatConnection connection = attributes == null ? null : (ChatConnection) attributes.get(ChatConnection.ATTRIBUTE);
        if (connection == null) {
            return;
        }
        connection.close(rooms);
        log.debug("[채팅 연결 종료] userId={} sessionId={} status={}", connection.userId(), event.getSessionId(),
                event.getCloseStatus());
    }
}
