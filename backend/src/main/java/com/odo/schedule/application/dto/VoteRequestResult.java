package com.odo.schedule.application.dto;

import java.time.Instant;

import com.odo.schedule.domain.entity.Vote;
import com.odo.schedule.domain.entity.VoteInvalidReason;
import com.odo.schedule.domain.entity.VoteRequest;
import com.odo.schedule.domain.entity.VoteRequestStatus;

public record VoteRequestResult(
		long requestId,
		long scheduleId,
		Vote choice,
		VoteRequestStatus status,
		VoteInvalidReason reason,
		Instant arrivedAt,
		Instant processedAt) {

	public static VoteRequestResult from(VoteRequest request) {
		return new VoteRequestResult(request.getId(), request.getScheduleId(), request.getChoice(), request.getStatus(),
				request.getReason(), request.getArrivedAt(), request.getProcessedAt());
	}

}
