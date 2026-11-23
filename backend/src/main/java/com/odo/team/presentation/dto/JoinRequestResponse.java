package com.odo.team.presentation.dto;

import java.time.Instant;

import com.odo.team.application.dto.JoinRequestResult;
import com.odo.team.domain.entity.JoinRequestStatus;

public record JoinRequestResponse(
		long requestId,
		long teamId,
		String teamName,
		JoinRequestStatus status,
		Instant requestedAt) {

	public static JoinRequestResponse from(JoinRequestResult result) {
		return new JoinRequestResponse(result.requestId(), result.teamId(), result.teamName(), result.status(),
				result.requestedAt());
	}

}
