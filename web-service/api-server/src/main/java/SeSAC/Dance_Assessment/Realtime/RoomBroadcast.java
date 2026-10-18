package SeSAC.Dance_Assessment.Realtime;

import SeSAC.Dance_Assessment.Domain.ChatMessageType;
import SeSAC.Dance_Assessment.Service.Chat.SavedMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * 방 메시지 방송 본문. Redis로 다른 서버에 보낼 때와 브라우저에 보낼 때 같은 모양이다.
 * <b>본문을 통째로 싣는다</b> — 받은 서버가 DB를 다시 읽지 않는다(메시지는 커밋 후 바뀌지 않는다).
 */
public record RoomBroadcast(
        long roomId,
        long seq,
        long messageId,
        Long senderId,
        String senderNickname,
        ChatMessageType type,
        String content,
        Instant createdAt,
        UUID clientMessageId) {

    public static RoomBroadcast of(SavedMessage m) {
        return new RoomBroadcast(m.roomId(), m.seq(), m.messageId(), m.senderId(), m.senderNickname(),
                m.type(), m.content(), m.createdAt(), m.clientMessageId());
    }
}
