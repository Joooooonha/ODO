package com.odo.practicevideo.domain.exception;

public class UnsupportedPracticeVideoTypeException extends RuntimeException {

	public UnsupportedPracticeVideoTypeException() {
		super("mp4(video/mp4)와 mov(video/quicktime)만 올릴 수 있다");
	}

}
