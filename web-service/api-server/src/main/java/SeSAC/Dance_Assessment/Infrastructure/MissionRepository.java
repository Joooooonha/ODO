package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.Mission;
import SeSAC.Dance_Assessment.Domain.TeamMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    /** 팀의 미션 목록. 삭제된 것은 빼고 최근 생성순. */
    @Query("select m from Mission m where m.team.id = :teamId and m.deletedAt is null"
            + " order by m.createdAt desc, m.id desc")
    List<Mission> findActiveByTeam(@Param("teamId") Long teamId);

    /**
     * 이 영상이 <b>삭제되지 않은 미션의 레퍼런스</b>이고, 보는 사람이 그 팀의 활동 중 멤버인가.
     *
     * <p>영상의 용도(MISSION)만으로는 어느 팀의 영상인지 알 수 없어 미션을 거쳐 확인한다.
     * 레퍼런스를 교체하면 옛 영상은 더 이상 어느 미션에도 걸려 있지 않으므로 업로더만 볼 수 있다.
     */
    @Query("select count(m) > 0 from Mission m, TeamMember tm"
            + " where m.referenceVideo.id = :videoId and m.deletedAt is null"
            + " and tm.team = m.team and tm.user.id = :userId and tm.status = :active")
    boolean isReferenceVisible(@Param("videoId") Long videoId, @Param("userId") Long userId,
                               @Param("active") TeamMemberStatus active);

    /** 이 영상이 삭제되지 않은 미션의 <b>제출 영상</b>이고, 보는 사람이 그 팀의 활동 중 멤버인가. */
    @Query("select count(s) > 0 from MissionSubmission s join s.target t join t.mission m, TeamMember tm"
            + " where s.video.id = :videoId and m.deletedAt is null"
            + " and tm.team = m.team and tm.user.id = :userId and tm.status = :active")
    boolean isSubmissionVisible(@Param("videoId") Long videoId, @Param("userId") Long userId,
                                @Param("active") TeamMemberStatus active);
}
