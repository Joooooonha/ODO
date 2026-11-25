package com.odo.schedule.domain.exception;

public class InvalidPracticeRoomPasswordException extends RuntimeException {

	public InvalidPracticeRoomPasswordException() {
		super("연습실 비밀번호 규칙에 맞지 않는다");
	}

}
