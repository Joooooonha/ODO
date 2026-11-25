package com.odo.schedule.presentation.dto;

import com.odo.schedule.application.dto.PracticeRoomResult;

/** 연습실 목록의 한 줄. 비밀번호와 메모는 넣지 않는다. 비밀번호를 내려주는 곳을 줄이기 위해서다. */
public record PracticeRoomSummaryResponse(
		long id,
		String name,
		String address,
		Integer rentalFee) {

	public static PracticeRoomSummaryResponse from(PracticeRoomResult result) {
		return new PracticeRoomSummaryResponse(result.id(), result.name(), result.address(), result.rentalFee());
	}

}
