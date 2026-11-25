package com.odo.schedule.presentation.dto;

import com.odo.schedule.application.dto.VoteRequestResult;
import com.odo.schedule.domain.entity.VoteRequestStatus;

/** 투표 접수 응답. 결과는 Location의 주소로 약 4초 뒤 물어본다. */
public record VoteAcceptedResponse(
		long requestId,
		VoteRequestStatus status) {

	public static VoteAcceptedResponse from(VoteRequestResult r) {
		return new VoteAcceptedResponse(r.requestId(), r.status());
	}

}
