package com.odo.schedule.presentation.dto;

import com.odo.schedule.application.dto.ParticipantResult;
import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.Vote;

public record ParticipantResponse(
		long accountId,
		String name,
		Integer generation,
		ParticipantStatus status,
		Vote vote) {

	public static ParticipantResponse from(ParticipantResult r) {
		return new ParticipantResponse(r.accountId(), r.name(), r.generation(), r.status(), r.vote());
	}

}
