package com.odo.schedule.presentation.dto;

import java.time.OffsetDateTime;

import com.odo.common.error.InvalidRequestException;
import com.odo.schedule.application.dto.UpdateScheduleCommand;

/**
 * 일정 수정의 본문. 만들기에서 투표 여부와 필수 참여자를 뺀 것이다. 그 값을 보내도 읽지 않는다.
 * 시작 시간과 연습 시간이 없으면 INVALID_REQUEST다.
 */
public record UpdateScheduleRequest(
		String title,
		OffsetDateTime startAt,
		Integer durationMinutes,
		Long practiceRoomId,
		String memo,
		OffsetDateTime voteDeadline,
		Integer capacity) {

	public UpdateScheduleCommand toCommand() {
		if (startAt == null || durationMinutes == null) {
			throw new InvalidRequestException();
		}
		return new UpdateScheduleCommand(title, startAt.toInstant(), durationMinutes, practiceRoomId, memo,
				voteDeadline == null ? null : voteDeadline.toInstant(), capacity);
	}

}
