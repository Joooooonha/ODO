package com.odo.schedule.presentation.dto;

import com.odo.common.error.InvalidRequestException;
import com.odo.schedule.domain.entity.ParticipantStatus;

/** 참여자 상태 변경의 본문. 정해진 값(REQUIRED, OPTIONAL, EXCLUDED)이 아니거나 없으면 INVALID_REQUEST다. */
public record ChangeParticipantStatusRequest(
		ParticipantStatus status) {

	public ParticipantStatus requireStatus() {
		if (status == null) {
			throw new InvalidRequestException();
		}
		return status;
	}

}
