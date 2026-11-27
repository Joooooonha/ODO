package com.odo.notification.domain.exception;

/** 순번(비우기의 upTo, 열기의 seq, 실시간 연결의 Last-Event-ID)이 음수다. */
public class InvalidNotificationSeqException extends RuntimeException {

	public InvalidNotificationSeqException() {
		super("순번은 0 이상이어야 한다");
	}

}
