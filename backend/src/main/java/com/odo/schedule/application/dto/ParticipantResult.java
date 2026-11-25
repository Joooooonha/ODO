package com.odo.schedule.application.dto;

import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.Vote;

/** 일정 참여자 한 사람. 기수는 그 팀에서의 지금 기수이고 없을 수 있다. 미투표면 vote가 null이다. */
public record ParticipantResult(
		long accountId,
		String name,
		Integer generation,
		ParticipantStatus status,
		Vote vote) {

}
