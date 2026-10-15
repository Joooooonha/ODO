package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.MissionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MissionSubmissionRepository extends JpaRepository<MissionSubmission, Long> {

    /** 제출물 보기 — 권한 확인에 미션·팀까지 필요하다. */
    @Query("select s from MissionSubmission s join fetch s.target t join fetch t.mission m"
            + " join fetch t.user where s.id = :id")
    Optional<MissionSubmission> findWithMission(@Param("id") Long id);

    @Query("select s from MissionSubmission s join fetch s.target t where t.mission.id = :missionId")
    List<MissionSubmission> findByMission(@Param("missionId") Long missionId);

    boolean existsByTargetId(Long targetId);
}
