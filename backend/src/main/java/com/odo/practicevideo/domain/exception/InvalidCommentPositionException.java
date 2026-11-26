package com.odo.practicevideo.domain.exception;

/** 코멘트의 시점이 0보다 작다(400). */
public class InvalidCommentPositionException extends RuntimeException {

	public InvalidCommentPositionException() {
		super("코멘트의 시점이 0보다 작다");
	}

}
