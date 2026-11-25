package com.odo.practicevideo.domain.exception;

public class InvalidCursorException extends RuntimeException {

	public InvalidCursorException() {
		super("커서를 읽을 수 없다");
	}

}
