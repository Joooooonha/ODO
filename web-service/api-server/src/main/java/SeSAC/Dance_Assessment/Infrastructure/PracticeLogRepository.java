package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.PracticeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeLogRepository extends JpaRepository<PracticeLog, Long> {

    // 특정 유저의 기록을 페이징해서 조회
    Page<PracticeLog> findByUserId(Long userId, Pageable pageable);
}
