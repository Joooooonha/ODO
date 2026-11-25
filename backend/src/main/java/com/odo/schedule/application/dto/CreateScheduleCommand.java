package com.odo.schedule.application.dto;

import java.time.Instant;
import java.util.List;

/** 일정 만들기의 입력. requiredAccountIds는 필수로 정할 사람이고 비어 있을 수 있다. */
public record CreateScheduleCommand(
		String title,
		Instant startAt,
		int durationMinutes,
		Long practiceRoomId,
		String memo,
		boolean votingEnabled,
		Instant voteDeadline,
		Integer capacity,
		List<Long> requiredAccountIds) {

}
