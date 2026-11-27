package com.odo.notification.domain.exception;

/** 목록의 커서(순번)가 1보다 작다. 코드는 연습 영상과 같은 INVALID_CURSOR다. */
public class InvalidNotificationCursorException extends RuntimeException {

	public InvalidNotificationCursorException() {
		super("커서는 1 이상이어야 한다");
	}

}
