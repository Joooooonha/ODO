package SeSAC.Dance_Assessment.Dto.Team;

import SeSAC.Dance_Assessment.Domain.Team;
import SeSAC.Dance_Assessment.Domain.TeamRole;

/**
 * 팀 카드와 Team Space 상단 한 줄에 쓰는 요약.
 *
 * <p>참여 코드는 싣지 않는다. 팀장만 볼 수 있어서 {@code GET /teams/{id}/join-code}로만 준다.
 *
 * @param memberCount 활동 중 인원 수
 */
public record TeamSummaryResponse(
        Long teamId,
        String name,
        TeamRole myRole,
        long memberCount) {

    public static TeamSummaryResponse of(Team team, TeamRole myRole, long memberCount) {
        return new TeamSummaryResponse(team.getId(), team.getTeamName(), myRole, memberCount);
    }
}
