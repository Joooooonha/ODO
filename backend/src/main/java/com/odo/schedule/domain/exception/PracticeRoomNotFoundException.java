package com.odo.schedule.domain.exception;

public class PracticeRoomNotFoundException extends RuntimeException {

	public PracticeRoomNotFoundException() {
		super("연습실이 없다");
	}

}
