package com.odo.team.presentation.dto;

import com.odo.team.application.dto.TeamMemberResult;
import com.odo.team.domain.entity.TeamRole;

public record TeamMemberResponse(long accountId, String name, Integer generation, TeamRole role) {

	public static TeamMemberResponse from(TeamMemberResult result) {
		return new TeamMemberResponse(result.accountId(), result.name(), result.generation(), result.role());
	}

}
