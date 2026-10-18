package SeSAC.Dance_Assessment.Dto.Chat;

import SeSAC.Dance_Assessment.Domain.ChatMessageType;

import java.time.Instant;
import java.util.UUID;

/**
 * 메시지 한 건.
 *
 * @param senderId        시스템 메시지는 null
 * @param content         시스템 메시지는 {@code {"event":"JOIN|LEAVE|KICK","targetUserId":7}}
 * @param clientMessageId <b>본인이 보낸 메시지에만</b> 싣는다. 새로 고침 후 보낼 목록에서 이미 저장된
 *                        것을 걸러내는 데 쓴다
 * @param createdAt       DB가 번호와 함께 찍은 시각(UTC 순간). 화면이 현지 시각으로 그린다
 * @param targetNickname  시스템 메시지만: 사건 대상의 <b>현재</b> 닉네임(조회 시점). 저장은 사건(id)만 한다
 */
public record ChatMessageResponse(
        long messageId,
        long seq,
        ChatMessageType type,
        Long senderId,
        String senderNickname,
        String content,
        UUID clientMessageId,
        Instant createdAt,
        String targetNickname) {
}
