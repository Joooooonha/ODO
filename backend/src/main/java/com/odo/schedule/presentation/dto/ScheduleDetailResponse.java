package com.odo.schedule.presentation.dto;

import java.time.Instant;

import com.odo.schedule.application.dto.ScheduleDetailResult;
import com.odo.schedule.application.dto.ScheduleListStatus;
import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.ScheduleType;
import com.odo.schedule.domain.entity.Vote;

public record ScheduleDetailResponse(
		long id,
		long teamId,
		ScheduleType type,
		String title,
		Instant startAt,
		int durationMinutes,
		Instant endAt,
		ScheduleListStatus status,
		ScheduleRoomResponse practiceRoom,
		String memo,
		boolean votingEnabled,
		Instant voteDeadline,
		boolean votingClosed,
		Integer capacity,
		long confirmedCount,
		long requiredCount,
		ParticipantStatus myStatus,
		Vote myVote,
		Instant createdAt,
		Instant updatedAt) {

	public static ScheduleDetailResponse from(ScheduleDetailResult r) {
		return new ScheduleDetailResponse(r.id(), r.teamId(), r.type(), r.title(), r.startAt(), r.durationMinutes(),
				r.endAt(), r.status(), ScheduleRoomResponse.from(r.practiceRoom()), r.memo(), r.votingEnabled(),
				r.voteDeadline(), r.votingClosed(), r.capacity(), r.confirmedCount(), r.requiredCount(), r.myStatus(),
				r.myVote(), r.createdAt(), r.updatedAt());
	}

}
