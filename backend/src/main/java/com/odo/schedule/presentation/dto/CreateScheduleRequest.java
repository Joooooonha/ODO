package com.odo.schedule.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import com.odo.common.error.InvalidRequestException;
import com.odo.schedule.application.dto.CreateScheduleCommand;

/**
 * 일정 만들기의 본문. 시각은 오프셋을 포함한 ISO-8601이다.
 * 시작 시간, 연습 시간, 투표 여부가 없으면 INVALID_REQUEST다(꼭 있어야 할 값이 없음). 제목이 없는 것은 INVALID_SCHEDULE_TITLE이다.
 * 필수 참여자 목록이 없으면 빈 목록이고, 목록 안에 null이 있으면 INVALID_REQUEST다.
 */
public record CreateScheduleRequest(
		String title,
		OffsetDateTime startAt,
		Integer durationMinutes,
		Long practiceRoomId,
		String memo,
		Boolean votingEnabled,
		OffsetDateTime voteDeadline,
		Integer capacity,
		List<Long> requiredAccountIds) {

	public CreateScheduleCommand toCommand() {
		if (startAt == null || durationMinutes == null || votingEnabled == null) {
			throw new InvalidRequestException();
		}
		List<Long> required = requiredAccountIds == null ? List.of() : requiredAccountIds;
		// List.of()의 contains(null)은 NPE를 던지므로 하나씩 본다
		if (required.stream().anyMatch(Objects::isNull)) {
			throw new InvalidRequestException();
		}
		return new CreateScheduleCommand(title, startAt.toInstant(), durationMinutes, practiceRoomId, memo,
				votingEnabled, voteDeadline == null ? null : voteDeadline.toInstant(), capacity, required);
	}

}
