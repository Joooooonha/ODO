package SeSAC.Dance_Assessment.Dto.Chat;

import SeSAC.Dance_Assessment.Domain.ChatRoomType;

/**
 * 방 목록 한 줄 ({@code GET /api/chat/rooms}).
 *
 * @param displayName 팀 방은 팀 이름, DM은 상대 닉네임
 * @param teamId      팀 방만
 * @param peerUserId  DM만
 * @param lastMessage 메시지가 없는 방은 null (메시지 없는 DM은 목록에 아예 나오지 않는다)
 * @param unread      {@code lastSeq − myReadSeq}. 이번 범위에서는 읽은 위치를 갱신하지 않아 계속 늘어난다
 */
public record ChatRoomSummaryResponse(
        long roomId,
        ChatRoomType type,
        String displayName,
        Long teamId,
        Long peerUserId,
        long lastSeq,
        ChatMessageResponse lastMessage,
        long myReadSeq,
        long unread) {
}
