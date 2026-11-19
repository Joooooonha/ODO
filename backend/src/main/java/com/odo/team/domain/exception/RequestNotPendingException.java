package com.odo.team.domain.exception;

public class RequestNotPendingException extends RuntimeException {

	public RequestNotPendingException() {
		super("이미 처리된 입장 요청이다");
	}

}
