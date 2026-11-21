package com.odo.performance.domain.exception;

public class InvalidPerformanceDescriptionException extends RuntimeException {

	public InvalidPerformanceDescriptionException() {
		super("공연 설명은 100자까지다");
	}

}
