package com.odo.team.application.dto;

import com.odo.team.domain.entity.Team;
import com.odo.team.domain.entity.TeamRole;

/**
 * 팀 상세. 팀 스페이스에 들어갔을 때 맨 위에 보이는 정보(팀 이름, 소개 문구, 팀장 이름, 내 역할)다.
 * 팀 상세 조회(GET /api/teams/{teamId})와 팀 생성(POST /api/teams) 직후의 응답에 쓴다.
 * 내 팀 목록의 한 항목인 MyTeamResult와 구분하려고 Detail을 붙였다. myRole은 보는 사람의 역할이다.
 */
public record TeamDetailResult(long id, String name, String description, String leaderName, TeamRole myRole) {

	public static TeamDetailResult of(Team team, String leaderName, TeamRole myRole) {
		return new TeamDetailResult(team.getId(), team.getName(), team.getDescription(), leaderName, myRole);
	}

}
