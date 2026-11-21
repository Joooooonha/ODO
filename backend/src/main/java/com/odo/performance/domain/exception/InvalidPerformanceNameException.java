package com.odo.performance.domain.exception;

public class InvalidPerformanceNameException extends RuntimeException {

	public InvalidPerformanceNameException() {
		super("공연 이름 규칙에 맞지 않는다");
	}

}
