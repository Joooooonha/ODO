package com.odo.practicevideo.domain.exception;

/** 코멘트 내용이 규칙에 맞지 않는다(400). 없음, 공백만, 300자 초과, 제어 문자. */
public class InvalidCommentContentException extends RuntimeException {

	public InvalidCommentContentException() {
		super("코멘트 내용이 규칙에 맞지 않는다");
	}

}
