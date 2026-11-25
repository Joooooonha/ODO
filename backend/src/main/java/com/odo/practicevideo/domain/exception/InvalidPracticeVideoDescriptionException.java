package com.odo.practicevideo.domain.exception;

public class InvalidPracticeVideoDescriptionException extends RuntimeException {

	public InvalidPracticeVideoDescriptionException() {
		super("연습 영상 설명은 이모지 없이 100자까지다");
	}

}
