package com.odo.practicevideo.domain.exception;

/** 내가 올리는 중인 영상을 열거나 고쳤다(409). 업로드가 끝나면 같은 요청이 성공할 수 있다. */
public class PracticeVideoNotReadyException extends RuntimeException {

	public PracticeVideoNotReadyException() {
		super("업로드가 끝나지 않은 영상이다");
	}

}
