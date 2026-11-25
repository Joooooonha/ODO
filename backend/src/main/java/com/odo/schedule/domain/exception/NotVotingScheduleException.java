package com.odo.schedule.domain.exception;

public class NotVotingScheduleException extends RuntimeException {

	public NotVotingScheduleException() {
		super("투표를 받지 않는 일정이다");
	}

}
