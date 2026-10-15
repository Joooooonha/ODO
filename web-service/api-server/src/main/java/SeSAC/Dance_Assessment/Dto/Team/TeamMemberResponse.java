package SeSAC.Dance_Assessment.Dto.Team;

import SeSAC.Dance_Assessment.Domain.TeamMember;
import SeSAC.Dance_Assessment.Domain.TeamRole;

public record TeamMemberResponse(
        Long userId,
        String nickname,
        TeamRole role) {

    public static TeamMemberResponse from(TeamMember member) {
        return new TeamMemberResponse(
                member.getUser().getId(), member.getUser().getNickname(), member.getRole());
    }
}
