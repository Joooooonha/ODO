package com.odo.schedule.domain.exception;

public class InvalidScheduleTitleException extends RuntimeException {

	public InvalidScheduleTitleException() {
		super("일정 제목 규칙에 맞지 않는다");
	}

}
