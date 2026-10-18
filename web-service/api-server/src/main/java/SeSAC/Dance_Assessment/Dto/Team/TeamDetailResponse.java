package SeSAC.Dance_Assessment.Dto.Team;

import SeSAC.Dance_Assessment.Domain.Team;
import SeSAC.Dance_Assessment.Domain.TeamRole;

/**
 * Team Space 상단 한 줄({@link TeamSummaryResponse}와 같은 필드) + 팀 채팅방 id.
 *
 * <p>목록 응답({@code GET /teams})에는 싣지 않는다 — 팀 수만큼 조회가 늘어난다.
 *
 * @param chatRoomId 채팅 탭이 바로 이 방을 연다
 */
public record TeamDetailResponse(
        Long teamId,
        String name,
        TeamRole myRole,
        long memberCount,
        Long chatRoomId) {

    public static TeamDetailResponse of(Team team, TeamRole myRole, long memberCount, Long chatRoomId) {
        return new TeamDetailResponse(team.getId(), team.getTeamName(), myRole, memberCount, chatRoomId);
    }
}
