package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.ChatRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByTeamId(Long teamId);

    /**
     * 팀 멤버 변화(참여·재활성화·나가기·추방)가 채팅 멤버를 바꾸기 전에 방 행을 잠근다.
     *
     * <p>메시지 저장도 이 행을 먼저 잠그므로({@code MessageWriter}) 둘이 한 줄로 선다.
     * 그래서 추방이 커밋된 뒤의 전송은 반드시 추방 결과를 보고 거부된다. 참여 때는 이 잠금
     * 아래에서 {@code last_seq}를 읽어야 새 멤버의 읽은 위치가 정확하다.
     *
     * <p>잠금 순서: {@code team_member} → <b>{@code chat_room}</b> → {@code chat_room_member}.
     * 모든 경로가 이 순서를 지켜 교착이 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.teamId = :teamId")
    Optional<ChatRoom> findByTeamIdForUpdate(@Param("teamId") Long teamId);
}
