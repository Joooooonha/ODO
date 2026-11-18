package com.odo.team.application.dto;

import com.odo.team.domain.Team;
import com.odo.team.domain.TeamRole;

/** 팀 상세. myRole은 보는 사람의 역할이다. */
public record TeamDetailResult(long id, String name, String description, String leaderName, TeamRole myRole) {

	public static TeamDetailResult of(Team team, String leaderName, TeamRole myRole) {
		return new TeamDetailResult(team.getId(), team.getName(), team.getDescription(), leaderName, myRole);
	}

}
