package com.odo.schedule.domain.exception;

public class InvalidDurationException extends RuntimeException {

	public InvalidDurationException() {
		super("연습 시간은 30분 단위로 30분 이상 1,440분 이하이고, 종료 시간이 현재 이후여야 한다");
	}

}
