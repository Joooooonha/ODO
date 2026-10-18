package SeSAC.Dance_Assessment.Realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * 나가는 메시지를 본다.
 *
 * <p><b>하트비트 무신호로 서버가 끊은 세션을 센다.</b> 메모리 브로커는 클라이언트가 30초(10초 × 3)
 * 동안 아무것도 보내지 않으면 DISCONNECT_ACK를 내보내고 세션을 닫는데, 이때만 원래 DISCONNECT
 * 메시지 헤더가 없다(클라이언트의 DISCONNECT·연결 종료 정리는 그 헤더를 단다 — spring-messaging
 * {@code SimpleBrokerMessageHandler.handleDisconnect} 확인).
 *
 * <p>나가는 방 메시지의 권한 확인(허용 방 캐시)은 권한·차단 단계(2-3)에서 여기에 붙는다.
 */
@Slf4j
@Component
public class ChatOutboundInterceptor implements ChannelInterceptor {

    private final ChatRealtimeMetrics metrics;

    public ChatOutboundInterceptor(ChatRealtimeMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.DISCONNECT_ACK
                && message.getHeaders().get(SimpMessageHeaderAccessor.DISCONNECT_MESSAGE_HEADER) == null) {
            metrics.disconnected(ChatRealtimeMetrics.HEARTBEAT);
            log.warn("[채팅 연결 종료] 하트비트 무신호 sessionId={}",
                    SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
        }
        return message;
    }
}
