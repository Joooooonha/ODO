package com.odo.performance.domain.exception;

public class PerformanceNotFoundException extends RuntimeException {

	public PerformanceNotFoundException() {
		super("공연이 없다");
	}

}
