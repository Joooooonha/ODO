package com.odo.schedule.application.dto;

import java.time.Instant;

import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.Vote;

/** 일정 목록의 한 줄. 연습실은 이름만 담는다. */
public record ScheduleSummaryResult(
		long id,
		String title,
		Instant startAt,
		Instant endAt,
		String practiceRoomName,
		boolean votingEnabled,
		Instant voteDeadline,
		Integer capacity,
		long confirmedCount,
		ParticipantStatus myStatus,
		Vote myVote) {

}
