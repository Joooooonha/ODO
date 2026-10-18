package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Service.Chat.ChatReply;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 보낸 그 세션에만 가는 프레임. 같은 사용자의 다른 탭에는 가지 않는다.
 *
 * <p>사용자 목적지에 세션 id 헤더를 붙여 보내면 Spring이 그 세션 하나로만 보낸다
 * ({@code DefaultUserDestinationResolver} 확인, {@code @SendToUser(broadcast = false)}와 같은 경로).
 * 반환값 대신 직접 보내는 이유: 확정·오류·차단 알림을 한 곳에서 보내고, 전송 실패를 테스트에서 주입할 수 있다.
 */
@Component
public class ChatReplySender {

    private final SimpMessagingTemplate template;

    public ChatReplySender(@Lazy @Qualifier("brokerMessagingTemplate") SimpMessagingTemplate template) {
        this.template = template;
    }

    public void send(ChatConnection connection, ChatReply reply) {
        if (reply instanceof ChatReply.Ack ack) {
            SavedMessage m = ack.message();
            toSession(connection, ChatDestinations.ACKS,
                    new ChatFrames.Ack(m.clientMessageId(), m.roomId(), m.seq(), m.createdAt(), ack.duplicate()));
        } else if (reply instanceof ChatReply.Error error) {
            toSession(connection, ChatDestinations.ACKS,
                    new ChatFrames.Error(error.clientMessageId(), error.roomId(), error.code()));
        }
        // ChatReply.None: 응답 없이 버린다
    }

    public void revoked(ChatConnection connection, long roomId) {
        toSession(connection, ChatDestinations.EVENTS, ChatFrames.Access.revoked(roomId));
    }

    private void toSession(ChatConnection connection, String destination, Object payload) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(connection.sessionId());
        headers.setLeaveMutable(true);
        MessageHeaders messageHeaders = headers.getMessageHeaders();
        template.convertAndSendToUser(connection.userName(), destination, payload, messageHeaders);
    }
}
