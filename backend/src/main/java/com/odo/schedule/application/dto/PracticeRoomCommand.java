package com.odo.schedule.application.dto;

/** 연습실 만들기와 수정의 입력. 대관비는 저장 타입의 상한을 넘는 값도 오류 코드로 알리려고 long으로 받는다. */
public record PracticeRoomCommand(
		String name,
		String address,
		String password,
		Long rentalFee,
		String memo) {

}
