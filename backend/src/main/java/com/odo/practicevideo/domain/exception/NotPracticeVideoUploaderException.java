package com.odo.practicevideo.domain.exception;

public class NotPracticeVideoUploaderException extends RuntimeException {

	public NotPracticeVideoUploaderException() {
		super("영상을 올린 디렉터만 할 수 있다");
	}

}
