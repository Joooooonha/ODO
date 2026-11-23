package com.odo.performance.domain.exception;

public class NotSongDirectorException extends RuntimeException {

	public NotSongDirectorException() {
		super("곡의 담당 디렉터만 할 수 있다");
	}

}
