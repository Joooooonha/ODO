package com.odo.team.application.dto;

import java.time.Instant;

import com.odo.team.domain.JoinRequest;
import com.odo.team.domain.JoinRequestStatus;
import com.odo.team.domain.Team;

/** 보낸 입장 요청. 요청 보내기의 결과와 내 요청 목록의 한 항목이다. */
public record JoinRequestResult(long requestId, long teamId, String teamName, JoinRequestStatus status,
		Instant requestedAt) {

	public static JoinRequestResult of(JoinRequest request, Team team) {
		return new JoinRequestResult(request.getId(), team.getId(), team.getName(), request.getStatus(),
				request.getRequestedAt());
	}

}
