package SeSAC.Dance_Assessment.Domain;

/**
 * 메시지 종류.
 *
 * <ul>
 *   <li>TEXT — 사람이 보낸 글. 보낸 사람이 있다</li>
 *   <li>SYSTEM — 입장·나가기·추방 안내. 보낸 사람이 없고, 내용은 문장이 아니라 사건
 *       ({@code {"event":"JOIN","targetUserId":7}})이다. 문장은 화면이 현재 닉네임으로 만든다</li>
 * </ul>
 */
public enum ChatMessageType {
    TEXT,
    SYSTEM
}
