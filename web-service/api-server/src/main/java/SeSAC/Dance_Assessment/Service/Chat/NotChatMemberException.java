package SeSAC.Dance_Assessment.Service.Chat;

/** 방이 없거나 ACTIVE 멤버가 아니다. 트랜잭션을 롤백시켜 올린 번호도 되돌린다. */
class NotChatMemberException extends RuntimeException {

    NotChatMemberException(long roomId, long userId) {
        super("채팅방 멤버가 아님: roomId=" + roomId + ", userId=" + userId, null, false, false);
    }
}
