package com.odo.performance.domain.exception;

public class PerformanceMemberNotFoundException extends RuntimeException {

	public PerformanceMemberNotFoundException() {
		super("공연 명단에 없는 사람이다");
	}

}
