package SeSAC.Dance_Assessment.Realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.io.IOException;

/**
 * 서버가 연결을 끊는다: STOMP ERROR 프레임(이유를 {@code message} 헤더에)을 보내고 닫는다.
 *
 * <p>ERROR를 나가는 채널로 보내면 Spring이 그 프레임을 보낸 뒤 연결을 닫는다
 * ({@code StompSubProtocolHandler.sendToClient}의 ERROR 처리 확인). 세션 하나에 여러 스레드가 동시에
 * 쓰지 않도록, 원래 세션에 직접 쓰지 않고 이 경로를 탄다.
 *
 * <p>먼저 {@code closed}를 세운다 — 그 순간부터 대기열에 남은 메시지는 처리되지 않는다.
 */
@Slf4j
@Component
public class ConnectionCloser {

    public static final String QUEUE_LIMIT_EXCEEDED = "QUEUE_LIMIT_EXCEEDED";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";

    private final MessageChannel clientOutboundChannel;
    private final RoomSubscriptions rooms;
    private final ChatRealtimeMetrics metrics;

    public ConnectionCloser(@Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
                            RoomSubscriptions rooms, ChatRealtimeMetrics metrics) {
        this.clientOutboundChannel = clientOutboundChannel;
        this.rooms = rooms;
        this.metrics = metrics;
    }

    public void closeWithError(ChatConnection connection, String reason, String metricReason) {
        connection.close(rooms);
        metrics.disconnected(metricReason);
        log.warn("[채팅 연결 종료] 이유={} userId={} sessionId={}", reason, connection.userId(), connection.sessionId());

        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(reason);
        error.setSessionId(connection.sessionId());
        try {
            clientOutboundChannel.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
        } catch (RuntimeException e) {
            log.warn("[채팅 연결 종료] ERROR 전송 실패, 직접 닫는다 sessionId={} 원인={}", connection.sessionId(), e.toString());
            closeQuietly(connection, CloseStatus.POLICY_VIOLATION.withReason(reason));
        }
    }

    /** 프로토콜 위반(프레임 여러 개를 한 메시지에 등). ERROR 없이 바로 닫는다. */
    public void closeForProtocol(ChatConnection connection, String detail) {
        connection.close(rooms);
        metrics.disconnected(ChatRealtimeMetrics.PROTOCOL);
        log.warn("[채팅 연결 종료] 프로토콜 위반 {} userId={} sessionId={}", detail, connection.userId(), connection.sessionId());
        closeQuietly(connection, CloseStatus.PROTOCOL_ERROR.withReason("ONE_FRAME_PER_MESSAGE"));
    }

    private static void closeQuietly(ChatConnection connection, CloseStatus status) {
        try {
            if (connection.webSocket() != null) {
                connection.webSocket().close(status);
            }
        } catch (IOException ignored) {
            // 이미 끊겼다
        }
    }
}
