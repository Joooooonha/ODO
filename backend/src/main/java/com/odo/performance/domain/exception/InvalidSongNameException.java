package com.odo.performance.domain.exception;

public class InvalidSongNameException extends RuntimeException {

	public InvalidSongNameException() {
		super("곡 이름 규칙에 맞지 않는다");
	}

}
