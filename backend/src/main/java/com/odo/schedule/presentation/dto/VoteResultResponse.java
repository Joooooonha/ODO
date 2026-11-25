package com.odo.schedule.presentation.dto;

import java.time.Instant;

import com.odo.schedule.application.dto.VoteRequestResult;
import com.odo.schedule.domain.entity.Vote;
import com.odo.schedule.domain.entity.VoteInvalidReason;
import com.odo.schedule.domain.entity.VoteRequestStatus;

/** 투표 결과. reason은 INVALID일 때만, processedAt은 처리한 뒤에만 값이 있다. */
public record VoteResultResponse(
		long requestId,
		long scheduleId,
		Vote choice,
		VoteRequestStatus status,
		VoteInvalidReason reason,
		Instant arrivedAt,
		Instant processedAt) {

	public static VoteResultResponse from(VoteRequestResult r) {
		return new VoteResultResponse(r.requestId(), r.scheduleId(), r.choice(), r.status(), r.reason(),
				r.arrivedAt(), r.processedAt());
	}

}
