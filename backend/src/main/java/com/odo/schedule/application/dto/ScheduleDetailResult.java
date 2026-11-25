package com.odo.schedule.application.dto;

import java.time.Instant;

import com.odo.schedule.domain.entity.ParticipantStatus;
import com.odo.schedule.domain.entity.ScheduleType;
import com.odo.schedule.domain.entity.Vote;

/**
 * 일정 상세. status와 votingClosed는 조회한 시각으로 계산한다.
 * confirmedCount는 참여로 투표한 선택 참여자 수(필수 제외), requiredCount는 필수 참여자 수다.
 * practiceRoom은 장소가 없으면 null이고, 삭제한 연습실이어도 정보를 담는다.
 */
public record ScheduleDetailResult(
		long id,
		long teamId,
		ScheduleType type,
		String title,
		Instant startAt,
		int durationMinutes,
		Instant endAt,
		ScheduleListStatus status,
		PracticeRoomResult practiceRoom,
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

}
