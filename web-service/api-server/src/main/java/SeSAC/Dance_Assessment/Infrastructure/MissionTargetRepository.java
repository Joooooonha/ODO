package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.MissionTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MissionTargetRepository extends JpaRepository<MissionTarget, Long> {

    Optional<MissionTarget> findByMissionIdAndUserId(Long missionId, Long userId);

    /** 제출 현황용. 나가거나 추방된 대상자도 그대로 들어 있다. */
    @Query("select t from MissionTarget t join fetch t.user where t.mission.id = :missionId order by t.id")
    List<MissionTarget> findByMissionWithUser(@Param("missionId") Long missionId);

    /**
     * 미션 목록의 숫자를 <b>한 번의 집계 쿼리</b>로 구한다 (미션마다 따로 세면 N+1).
     *
     * <p>결과 한 행 = [미션 id, 대상자 수, 제출 수, 내가 대상자인가(0/1), 내가 제출했는가(0/1)].
     * 대상자가 나가거나 추방돼도 대상자 행과 제출 행은 남으므로, 숫자는 그 직전 상태 그대로다.
     */
    @Query("select t.mission.id, count(t), count(s),"
            + " sum(case when t.user.id = :userId then 1 else 0 end),"
            + " sum(case when t.user.id = :userId and s.id is not null then 1 else 0 end)"
            + " from MissionTarget t left join MissionSubmission s on s.target = t"
            + " where t.mission.id in :missionIds group by t.mission.id")
    List<Object[]> summarize(@Param("missionIds") Collection<Long> missionIds, @Param("userId") Long userId);
}
