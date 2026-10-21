package SeSAC.Dance_Assessment.Domain;

/**
 * 채팅 멤버 상태. 팀 채팅방에서는 팀 멤버 상태를 같은 트랜잭션에서 따라간다
 * (참여·재활성화 → ACTIVE, 나가기 → LEFT, 추방 → KICKED).
 *
 * <p>DM 멤버는 팀 멤버 변화의 영향을 받지 않는다 — 팀을 나가도 이미 있는 DM은 계속 쓴다.
 */
public enum ChatMemberStatus {
    ACTIVE,
    LEFT,
    KICKED
}
