package SeSAC.Dance_Assessment.Service.Chat;

import SeSAC.Dance_Assessment.Domain.ChatMessageType;

import java.time.Instant;
import java.util.UUID;

/**
 * 커밋된(또는 이미 있던) 메시지 한 건. 확정 응답과 서버 간 방송이 이 값을 그대로 쓴다 —
 * 메시지는 저장 후 바뀌지 않으므로 받은 쪽이 DB를 다시 읽을 필요가 없다.
 *
 * @param senderId       시스템 메시지는 null
 * @param senderNickname 시스템 메시지는 null
 */
public record SavedMessage(
        long messageId,
        long roomId,
        long seq,
        Long senderId,
        String senderNickname,
        ChatMessageType type,
        String content,
        UUID clientMessageId,
        Instant createdAt) {
}
