package com.odo.team.presentation.dto;

import com.odo.team.application.dto.TeamDetailResult;
import com.odo.team.domain.entity.TeamRole;

public record TeamDetailResponse(
		long id,
		String name,
		String description,
		String leaderName,
		TeamRole myRole) {

	public static TeamDetailResponse from(TeamDetailResult result) {
		return new TeamDetailResponse(result.id(), result.name(), result.description(), result.leaderName(),
				result.myRole());
	}

}
