package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Domain.ChatMemberStatus;
import SeSAC.Dance_Assessment.Infrastructure.ChatRoomMemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.OptionalLong;

/**
 * 들어오는 STOMP 메시지를 <b>처리 직전에</b> 거른다(세션 대기열에서 꺼낼 때 불린다).
 *
 * <ul>
 *   <li><b>끊긴 연결(closed)의 메시지는 버린다</b>(4.3). {@code null}을 돌려주면 Spring은 그 메시지를
 *       버리고 대기열의 다음으로 넘어간다. 단 DISCONNECT는 통과시킨다 — Spring이 연결 종료 때 직접
 *       넣는 메시지라 막으면 브로커가 구독을 치우지 못한다</li>
 *   <li>SEND: 대기열 수를 하나 빼고 대기 시간을 기록한다(4.4). 목적지 형식이 다르면 버린다</li>
 *   <li>SUBSCRIBE: 목적지가 {@code /topic/rooms.{id}}면 ACTIVE 멤버인지 확인하고, 통과한 <b>직후</b>
 *       방 구독표를 갱신한다(P1 — Spring의 구독 이벤트는 대기열에 넣는 순간, 권한 확인 전에 발행된다).
 *       거부하면 버리고 그 세션에 REVOKED를 보낸다(P2 — 순서 보존 모드에서는 여기서 던진 예외가
 *       클라이언트에 전달되지 않는다). 개인 목적지 두 개 외의 다른 목적지는 버린다</li>
 *   <li>UNSUBSCRIBE: 구독 id로 방을 찾아 표에서 뺀다</li>
 * </ul>
 *
 * <p>구독 권한은 지금 DB로 바로 확인한다. 허용 방 캐시·제어 이벤트·5초 재확인은 권한·차단
 * 단계(2-3)에서 이 자리를 바꾼다.
 */
@Slf4j
@Component
public class ChatInboundInterceptor implements ChannelInterceptor {

    private final RoomSubscriptions rooms;
    private final ChatRoomMemberRepository members;
    private final ChatReplySender replySender;
    private final ChatRealtimeMetrics metrics;

    public ChatInboundInterceptor(RoomSubscriptions rooms, ChatRoomMemberRepository members,
                                  @Lazy ChatReplySender replySender, ChatRealtimeMetrics metrics) {
        this.rooms = rooms;
        this.members = members;
        this.replySender = replySender;
        this.metrics = metrics;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;   // 하트비트 등
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT) {
            return message;
        }
        ChatConnection connection = connectionOf(accessor);
        if (connection == null) {
            log.warn("[STOMP] 연결 상태 없음 command={} sessionId={}", command, accessor.getSessionId());
            return null;
        }

        return switch (command) {
            case SEND -> onSend(connection, accessor, message);
            case SUBSCRIBE -> onSubscribe(connection, accessor, message);
            case UNSUBSCRIBE -> {
                if (accessor.getSubscriptionId() != null) {
                    connection.removeSubscription(accessor.getSubscriptionId(), rooms);
                }
                yield message;
            }
            default -> connection.isClosed() ? null : message;
        };
    }

    private Message<?> onSend(ChatConnection connection, StompHeaderAccessor accessor, Message<?> message) {
        metrics.queueWait(connection.sendStarted());
        if (connection.isClosed()) {
            return null;
        }
        if (ChatDestinations.roomOfSend(accessor.getDestination()).isEmpty()) {
            log.warn("[STOMP] 알 수 없는 전송 목적지 destination={} userId={}", accessor.getDestination(), connection.userId());
            return null;
        }
        return message;
    }

    private Message<?> onSubscribe(ChatConnection connection, StompHeaderAccessor accessor, Message<?> message) {
        if (connection.isClosed()) {
            return null;
        }
        String destination = accessor.getDestination();
        if (ChatDestinations.USER_ACKS.equals(destination) || ChatDestinations.USER_EVENTS.equals(destination)) {
            return message;
        }
        OptionalLong room = ChatDestinations.roomOfTopic(destination);
        if (room.isEmpty() || accessor.getSubscriptionId() == null) {
            log.warn("[STOMP 구독 거부] 허용하지 않는 목적지 destination={} userId={}", destination, connection.userId());
            metrics.subscribeDenied();
            return null;
        }
        long roomId = room.getAsLong();
        boolean member = members.findByRoomIdAndUserId(roomId, connection.userId())
                .filter(m -> m.getStatus() == ChatMemberStatus.ACTIVE)
                .isPresent();
        if (!member) {
            log.warn("[STOMP 구독 거부] 멤버 아님 roomId={} userId={}", roomId, connection.userId());
            metrics.subscribeDenied();
            replySender.revoked(connection, roomId);
            return null;
        }
        if (!connection.addSubscription(accessor.getSubscriptionId(), roomId, rooms)) {
            return null;   // 그사이 끊겼다
        }
        return message;
    }

    @SuppressWarnings("unchecked")
    private static ChatConnection connectionOf(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        return attributes == null ? null : (ChatConnection) attributes.get(ChatConnection.ATTRIBUTE);
    }
}
