package com.odo.notification.domain.exception;

/** 알림이 없거나 남의 알림이다. 남의 알림이 있다는 것을 드러내지 않으려고 같은 404다. */
public class NotificationNotFoundException extends RuntimeException {

	public NotificationNotFoundException() {
		super("알림이 없다");
	}

}
