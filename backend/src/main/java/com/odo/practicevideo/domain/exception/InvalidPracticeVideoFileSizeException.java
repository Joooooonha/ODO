package com.odo.practicevideo.domain.exception;

public class InvalidPracticeVideoFileSizeException extends RuntimeException {

	public InvalidPracticeVideoFileSizeException() {
		super("파일 크기는 1바이트부터 1GB까지다");
	}

}
