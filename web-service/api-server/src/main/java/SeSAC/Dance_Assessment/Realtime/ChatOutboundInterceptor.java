package SeSAC.Dance_Assessment.Realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * 나가는 메시지를 본다.
 *
 * <p><b>하트비트 무신호로 서버가 끊은 세션을 센다.</b> 메모리 브로커는 클라이언트가 30초(10초 × 3)
 * 동안 아무것도 보내지 않으면 DISCONNECT_ACK를 내보내고 세션을 닫는데, 이때만 원래 DISCONNECT
 * 메시지 헤더가 없다(클라이언트의 DISCONNECT·연결 종료 정리는 그 헤더를 단다 — spring-messaging
 * {@code SimpleBrokerMessageHandler.handleDisconnect} 확인).
 *
 * <p><b>서버 → 브라우저 전달 직전의 권한 확인(6.2)</b>: 나가는 방 메시지({@code /topic/rooms.{id}})의 방이
 * 그 세션의 허용 방 캐시에 <b>아직</b> 있는지 본다. 없으면 버린다. 추방·나가기의 제어 이벤트는 캐시에서
 * 먼저 지우므로, 브로커 구독 청소가 늦거나 실패해도 이 검사가 막는다. 목적지는 클라이언트가 고칠 수
 * 있으므로 서버 쪽 사실(캐시)로만 판단한다.
 *
 * <p><b>종류별로 센다</b>({@code chat.outbound.frames{type}}): 방 프레임은 받는 쪽이 붙인 종류 헤더(MESSAGE/READ),
 * 개인 목적지는 acks → ACK, events → EVENT. 버린 프레임은 세지 않는다.
 */
@Slf4j
@Component
public class ChatOutboundInterceptor implements ChannelInterceptor {

    private final ChatRealtimeMetrics metrics;
    private final ChatConnectionRegistry registry;

    public ChatOutboundInterceptor(ChatRealtimeMetrics metrics, ChatConnectionRegistry registry) {
        this.metrics = metrics;
        this.registry = registry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type == SimpMessageType.MESSAGE) {
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            OptionalLong room = ChatDestinations.roomOfTopic(destination);
            if (room.isPresent()) {
                ChatConnection connection = registry.get(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
                if (connection == null || !connection.isAllowed(room.getAsLong())) {
                    metrics.outboundDropped();
                    log.debug("[채팅 전달 차단] 허용 방 아님 roomId={} sessionId={}", room.getAsLong(),
                            SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
                    return null;
                }
                Object frameType = message.getHeaders().get(RoomChannelListener.FRAME_TYPE_HEADER);
                metrics.outboundFrame(frameType == null ? ChatRealtimeMetrics.MESSAGE : frameType.toString());
            } else if (destination != null && destination.endsWith(ChatDestinations.ACKS)) {
                metrics.outboundFrame(ChatRealtimeMetrics.ACK);
            } else if (destination != null && destination.endsWith(ChatDestinations.EVENTS)) {
                metrics.outboundFrame(ChatRealtimeMetrics.EVENT);
            }
            return message;
        }
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.DISCONNECT_ACK
                && message.getHeaders().get(SimpMessageHeaderAccessor.DISCONNECT_MESSAGE_HEADER) == null) {
            metrics.disconnected(ChatRealtimeMetrics.HEARTBEAT);
            log.warn("[채팅 연결 종료] 하트비트 무신호 sessionId={}",
                    SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
        }
        return message;
    }
}
