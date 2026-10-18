package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 채팅 멤버. <b>상태를 바꾸는 문장은 여기 있는 두 개뿐이고, 둘 다 {@code modified_at = now()}를
 * 직접 넣는다.</b> 서버마다 5초마다 이 칸으로 최근 변경을 찾기 때문에 빠지면 안 된다
 * (JPA 감사 기능은 이런 UPDATE에서 동작하지 않는다).
 */
public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    Optional<ChatRoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

    /**
     * 팀 참여(신규)와 재활성화를 한 문장으로. 행이 없으면 만들고, 있으면 다시 ACTIVE로 만든다.
     * 읽은 위치는 예전 값을 되살리지 않고 지금의 방 {@code last_seq}로 둔다.
     */
    @Modifying
    @Query(value = "INSERT INTO chat_room_member (room_id, user_id, status, last_read_seq)"
            + " VALUES (:roomId, :userId, 'ACTIVE', :lastReadSeq)"
            + " ON CONFLICT ON CONSTRAINT uk_chat_room_member_room_user DO UPDATE"
            + " SET status = 'ACTIVE', last_read_seq = EXCLUDED.last_read_seq, modified_at = now()",
            nativeQuery = true)
    int activate(@Param("roomId") Long roomId, @Param("userId") Long userId,
                 @Param("lastReadSeq") long lastReadSeq);

    /** 나가기(LEFT)·추방(KICKED). 활동 중인 행만 바꾼다. 바뀐 행 수를 돌려준다. */
    @Modifying
    @Query(value = "UPDATE chat_room_member SET status = :status, modified_at = now()"
            + " WHERE room_id = :roomId AND user_id = :userId AND status = 'ACTIVE'",
            nativeQuery = true)
    int deactivate(@Param("roomId") Long roomId, @Param("userId") Long userId,
                   @Param("status") String status);
}
