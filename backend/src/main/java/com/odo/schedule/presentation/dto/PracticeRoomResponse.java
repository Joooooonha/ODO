package com.odo.schedule.presentation.dto;

import java.time.Instant;

import com.odo.schedule.application.dto.PracticeRoomResult;

/** 연습실 상세. 비밀번호는 평문이다. */
public record PracticeRoomResponse(
		long id,
		long teamId,
		String name,
		String address,
		String password,
		Integer rentalFee,
		String memo,
		Instant createdAt,
		Instant updatedAt) {

	public static PracticeRoomResponse from(PracticeRoomResult result) {
		return new PracticeRoomResponse(result.id(), result.teamId(), result.name(), result.address(), result.password(),
				result.rentalFee(), result.memo(), result.createdAt(), result.updatedAt());
	}

}
