package com.odo.team.presentation.dto;

import com.odo.team.application.dto.InvitePreviewResult;
import com.odo.team.application.dto.InviteStatus;

public record InvitePreviewResponse(long teamId, String teamName, String leaderName, String description,
		InviteStatus myStatus, Long myRequestId) {

	public static InvitePreviewResponse from(InvitePreviewResult result) {
		return new InvitePreviewResponse(result.teamId(), result.teamName(), result.leaderName(), result.description(),
				result.myStatus(), result.myRequestId());
	}

}
