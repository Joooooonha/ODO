package com.odo.schedule.domain.exception;

public class InvalidPracticeRoomMemoException extends RuntimeException {

	public InvalidPracticeRoomMemoException() {
		super("연습실 메모 규칙에 맞지 않는다");
	}

}
