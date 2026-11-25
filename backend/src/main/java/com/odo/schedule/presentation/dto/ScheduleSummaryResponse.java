package com.odo.schedule.presentation.dto;

import java.time.Instant;

import com.odo.schedule.application.dto.ScheduleSummaryResult;
import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.Vote;

public record ScheduleSummaryResponse(
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

	public static ScheduleSummaryResponse from(ScheduleSummaryResult r) {
		return new ScheduleSummaryResponse(r.id(), r.title(), r.startAt(), r.endAt(), r.practiceRoomName(),
				r.votingEnabled(), r.voteDeadline(), r.capacity(), r.confirmedCount(), r.myStatus(), r.myVote());
	}

}
