package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

/** 조회 전용. 저장은 {@code MessageWriter}가 SQL로 직접 한다. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    long countByRoomId(Long roomId);
}
