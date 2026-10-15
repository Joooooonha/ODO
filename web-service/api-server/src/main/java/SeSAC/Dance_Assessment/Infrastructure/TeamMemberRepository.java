package SeSAC.Dance_Assessment.Infrastructure;

import SeSAC.Dance_Assessment.Domain.TeamMember;
import SeSAC.Dance_Assessment.Domain.TeamMemberStatus;
import SeSAC.Dance_Assessment.Domain.TeamRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    /** 권한 확인용. 상태를 바꾸지 않는 조회에서만 쓴다. */
    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    /**
     * 상태를 바꾸기 전에 반드시 이걸로 읽는다 (재참여·나가기·추방 모두).
     *
     * <p>행 잠금 없이 읽으면 나가기와 추방이 동시에 왔을 때 둘 다 ACTIVE를 보고 통과해,
     * 나중에 커밋한 쪽이 앞의 결과를 덮어쓴다. LEFT가 KICKED를 덮으면 추방된 사람이
     * 다시 참여할 수 있게 된다. 잠그면 나중 요청은 앞 요청이 커밋한 상태를 보고 거부된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TeamMember m where m.team.id = :teamId and m.user.id = :userId")
    Optional<TeamMember> findForUpdate(@Param("teamId") Long teamId, @Param("userId") Long userId);

    /** 내 팀 목록. 옮겨 담은 행은 joined_at이 모두 같아서 team_id를 두 번째 기준으로 둔다. */
    @Query("select m from TeamMember m join fetch m.team"
            + " where m.user.id = :userId and m.status = :status"
            + " order by m.joinedAt desc, m.team.id desc")
    List<TeamMember> findMyTeams(@Param("userId") Long userId,
                                 @Param("status") TeamMemberStatus status);

    @Query("select m from TeamMember m join fetch m.user"
            + " where m.team.id = :teamId and m.status = :status"
            + " order by m.joinedAt asc, m.id asc")
    List<TeamMember> findByTeamWithUser(@Param("teamId") Long teamId,
                                        @Param("status") TeamMemberStatus status);

    @Query("select m from TeamMember m join fetch m.user"
            + " where m.team.id = :teamId and m.role = :role")
    Optional<TeamMember> findByTeamAndRole(@Param("teamId") Long teamId, @Param("role") TeamRole role);

    long countByTeamIdAndStatus(Long teamId, TeamMemberStatus status);

    /** 팀별 인원 수를 한 번에 센다. 결과는 [teamId, count] 쌍. */
    @Query("select m.team.id, count(m) from TeamMember m"
            + " where m.team.id in :teamIds and m.status = :status group by m.team.id")
    List<Object[]> countByTeamIds(@Param("teamIds") Collection<Long> teamIds,
                                  @Param("status") TeamMemberStatus status);
}
