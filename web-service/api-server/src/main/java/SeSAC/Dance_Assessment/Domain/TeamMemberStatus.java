package SeSAC.Dance_Assessment.Domain;

/**
 * 팀 멤버 상태. 멤버 기록은 지우지 않고 상태만 바꾼다 —
 * 나간 사람이 남긴 기록에 "누가 남겼는지"가 계속 보여야 하기 때문이다.
 *
 * <p>전이는 세 가지뿐이다: ACTIVE → LEFT(나가기), ACTIVE → KICKED(추방),
 * LEFT → ACTIVE(재참여). 추방은 되돌릴 수 없다.
 */
public enum TeamMemberStatus {
    ACTIVE,
    LEFT,
    KICKED
}
