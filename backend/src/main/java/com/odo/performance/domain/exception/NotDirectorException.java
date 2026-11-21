package com.odo.performance.domain.exception;

public class NotDirectorException extends RuntimeException {

	public NotDirectorException() {
		super("디렉터만 할 수 있다");
	}

}
