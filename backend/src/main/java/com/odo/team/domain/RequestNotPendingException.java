package com.odo.team.domain;

public class RequestNotPendingException extends RuntimeException {

	public RequestNotPendingException() {
		super("이미 처리된 입장 요청이다");
	}

}
