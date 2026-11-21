package com.odo.performance.domain.exception;

public class AlreadyInPerformanceException extends RuntimeException {

	public AlreadyInPerformanceException() {
		super("이미 공연 명단에 있는 사람이다");
	}

}
