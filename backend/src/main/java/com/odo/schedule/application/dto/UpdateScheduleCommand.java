package com.odo.schedule.application.dto;

import java.time.Instant;

/** 일정 수정의 입력. 만들기에서 투표 여부와 필수 참여자를 뺀 것이다. */
public record UpdateScheduleCommand(
		String title,
		Instant startAt,
		int durationMinutes,
		Long practiceRoomId,
		String memo,
		Instant voteDeadline,
		Integer capacity) {

}
