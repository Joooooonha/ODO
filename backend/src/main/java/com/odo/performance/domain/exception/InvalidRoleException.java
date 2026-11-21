package com.odo.performance.domain.exception;

public class InvalidRoleException extends RuntimeException {

	public InvalidRoleException() {
		super("디렉터 지정만 할 수 있다");
	}

}
