package com.odo.schedule.domain.exception;

public class InvalidPracticeRoomNameException extends RuntimeException {

	public InvalidPracticeRoomNameException() {
		super("연습실 이름 규칙에 맞지 않는다");
	}

}
