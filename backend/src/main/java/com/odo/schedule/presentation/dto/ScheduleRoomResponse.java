package com.odo.schedule.presentation.dto;

import com.odo.schedule.application.dto.PracticeRoomResult;

/** 일정 상세에 담는 연습실. 삭제한 연습실이어도 정보를 그대로 담고 deleted가 true다. */
public record ScheduleRoomResponse(
		long id,
		String name,
		String address,
		String password,
		Integer rentalFee,
		String memo,
		boolean deleted) {

	public static ScheduleRoomResponse from(PracticeRoomResult result) {
		if (result == null) {
			return null;
		}
		return new ScheduleRoomResponse(result.id(), result.name(), result.address(), result.password(),
				result.rentalFee(), result.memo(), result.deleted());
	}

}
