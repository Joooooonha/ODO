package com.odo.schedule.domain.exception;

public class InvalidScheduleMemoException extends RuntimeException {

	public InvalidScheduleMemoException() {
		super("일정 메모 규칙에 맞지 않는다");
	}

}
