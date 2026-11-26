package com.odo.practicevideo.domain.exception;

public class InvalidPracticeVideoTitleException extends RuntimeException {

	public InvalidPracticeVideoTitleException() {
		super("연습 영상 제목은 이모지와 줄바꿈 없이 1~100자다");
	}

}
