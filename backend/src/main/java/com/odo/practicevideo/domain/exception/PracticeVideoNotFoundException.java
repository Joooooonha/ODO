package com.odo.practicevideo.domain.exception;

/** 영상이 없거나, 삭제 중이거나, 명단에 없거나, 남이 올리는 중이다(404). 있다는 것을 드러내지 않는다. */
public class PracticeVideoNotFoundException extends RuntimeException {

	public PracticeVideoNotFoundException() {
		super("연습 영상이 없다");
	}

}
