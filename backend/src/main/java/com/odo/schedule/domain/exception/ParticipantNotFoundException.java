package com.odo.schedule.domain.exception;

public class ParticipantNotFoundException extends RuntimeException {

	public ParticipantNotFoundException() {
		super("그 일정의 참여자가 아니다");
	}

}
