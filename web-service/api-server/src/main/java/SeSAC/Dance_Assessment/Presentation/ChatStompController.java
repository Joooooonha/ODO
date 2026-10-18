package SeSAC.Dance_Assessment.Presentation;

import SeSAC.Dance_Assessment.Dto.Chat.ChatSendRequest;
import SeSAC.Dance_Assessment.Realtime.ChatConnection;
import SeSAC.Dance_Assessment.Realtime.ChatReplySender;
import SeSAC.Dance_Assessment.Service.Chat.ChatReply;
import SeSAC.Dance_Assessment.Service.Chat.ChatSendHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * 메시지 전송 ({@code SEND /app/rooms/{roomId}/messages}, 본문 {@code {clientMessageId, content, resume?}}).
 *
 * <p>세션 차례 처리 스레드에서 <b>끝까지</b> 처리한다 — 저장을 다른 스레드로 넘기지 않는다. 이 메서드가
 * 반환되는 순간 Spring이 같은 세션의 다음 메시지를 내보내므로, 넘기면 같은 사람의 순서가 섞인다.
 *
 * <p>응답(확정·오류)은 보낸 세션에만 간다. 멈춤 표시는 {@link ChatSendHandler}가 응답을 만들기 전에
 * 이미 세웠으므로, 응답 전송이 실패해도 다음 메시지는 처리되지 않는다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatSendHandler sendHandler;
    private final ChatReplySender replySender;
    private final ObjectMapper objectMapper;

    @MessageMapping("/rooms/{roomId}/messages")
    public void send(@DestinationVariable long roomId, Message<byte[]> message, SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        ChatConnection connection = attributes == null ? null : (ChatConnection) attributes.get(ChatConnection.ATTRIBUTE);
        if (connection == null) {
            return;
        }
        ChatReply reply = sendHandler.handle(connection.sendState(), connection.userId(), roomId,
                parse(message.getPayload()));
        replySender.send(connection, reply);
    }

    /**
     * 본문은 변환기를 거치지 않고 직접 읽는다 — 변환에 실패하면 메서드가 불리지 않아 멈춤·오류 응답 규칙을
     * 적용할 수 없다. 읽지 못하는 본문은 빈 요청으로 넘긴다 → 멈춤 중이면 버려지고, 아니면 INVALID_MESSAGE.
     */
    private ChatSendRequest parse(byte[] body) {
        try {
            ChatSendRequest request = objectMapper.readValue(body, ChatSendRequest.class);
            return request == null ? new ChatSendRequest(null, null, null) : request;
        } catch (Exception e) {
            return new ChatSendRequest(null, null, null);
        }
    }
}
