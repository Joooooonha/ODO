package com.odo.team.presentation.dto;

import com.odo.team.application.dto.MyTeamResult;
import com.odo.team.domain.entity.TeamRole;

public record MyTeamResponse(long id, String name, TeamRole myRole) {

	public static MyTeamResponse from(MyTeamResult result) {
		return new MyTeamResponse(result.id(), result.name(), result.myRole());
	}

}
