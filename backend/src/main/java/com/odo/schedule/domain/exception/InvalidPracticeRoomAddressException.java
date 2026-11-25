package com.odo.schedule.domain.exception;

public class InvalidPracticeRoomAddressException extends RuntimeException {

	public InvalidPracticeRoomAddressException() {
		super("연습실 주소 규칙에 맞지 않는다");
	}

}
