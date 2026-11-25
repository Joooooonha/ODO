package com.odo.schedule.domain.exception;

public class InvalidCapacityException extends RuntimeException {

	public InvalidCapacityException() {
		super("정원은 1 이상이어야 한다");
	}

}
