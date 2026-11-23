package com.odo.team.application.dto;

import java.time.Instant;

import com.odo.team.domain.entity.JoinRequest;

/** 팀장의 대기 목록의 한 항목. name은 요청한 사람의 이름이다. */
public record PendingJoinRequestResult(
		long requestId,
		long accountId,
		String name,
		Integer generation,
		Instant requestedAt) {

	public static PendingJoinRequestResult of(JoinRequest request, String name) {
		return new PendingJoinRequestResult(request.getId(), request.getAccountId(), name, request.getGeneration(),
				request.getRequestedAt());
	}

}
