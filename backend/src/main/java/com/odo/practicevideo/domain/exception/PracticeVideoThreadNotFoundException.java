package com.odo.practicevideo.domain.exception;

/** 스레드가 없거나, 그 영상을 볼 수 없거나, 영상이 삭제 중이다(404). 있다는 것을 드러내지 않는다. */
public class PracticeVideoThreadNotFoundException extends RuntimeException {

	public PracticeVideoThreadNotFoundException() {
		super("스레드가 없다");
	}

}
