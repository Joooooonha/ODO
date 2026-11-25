package com.odo.schedule.presentation.dto;

import com.odo.schedule.application.dto.PracticeRoomCommand;

/**
 * 연습실 만들기와 수정의 본문. 이름만 필수이고, 비어 있는 선택 항목은 없는 것으로 본다.
 * 대관비는 int 상한을 넘는 값도 INVALID_RENTAL_FEE로 알리려고 Long으로 받는다.
 */
public record PracticeRoomRequest(
		String name,
		String address,
		String password,
		Long rentalFee,
		String memo) {

	public PracticeRoomCommand toCommand() {
		return new PracticeRoomCommand(name, address, password, rentalFee, memo);
	}

}
