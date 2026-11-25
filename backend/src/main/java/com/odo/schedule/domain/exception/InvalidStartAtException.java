package com.odo.schedule.domain.exception;

public class InvalidStartAtException extends RuntimeException {

	public InvalidStartAtException() {
		super("시작 시간은 현재 이후의 10분 단위여야 한다");
	}

}
