package com.odo.schedule.domain.exception;

public class InvalidPracticeRoomException extends RuntimeException {

	public InvalidPracticeRoomException() {
		super("고를 수 없는 연습실이다");
	}

}
