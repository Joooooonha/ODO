package com.odo.team.presentation.dto;

import java.time.Instant;

import com.odo.team.application.dto.PendingJoinRequestResult;

public record PendingJoinRequestResponse(
		long requestId,
		long accountId,
		String name,
		Integer generation,
		Instant requestedAt) {

	public static PendingJoinRequestResponse from(PendingJoinRequestResult result) {
		return new PendingJoinRequestResponse(result.requestId(), result.accountId(), result.name(),
				result.generation(), result.requestedAt());
	}

}
