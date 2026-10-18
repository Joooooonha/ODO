package SeSAC.Dance_Assessment.Service.Chat;

import java.util.UUID;

/**
 * 팀 채팅방에 남길 사건(입장·나가기·추방). 팀 트랜잭션 안에서 발행하고, 커밋된 뒤에만
 * {@link TeamChatEventListener}가 받는다.
 *
 * @param eventId 사건이 생길 때 한 번 만든 UUID. 안내 메시지의 {@code client_message_id}가 되어,
 *                같은 사건을 다시 저장해도 한 건이다
 */
public record TeamChatEvent(long roomId, long targetUserId, Kind kind, UUID eventId) {

    public enum Kind {
        JOIN,
        LEAVE,
        KICK
    }

    public static TeamChatEvent of(long roomId, long targetUserId, Kind kind) {
        return new TeamChatEvent(roomId, targetUserId, kind, UUID.randomUUID());
    }
}
