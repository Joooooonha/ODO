package com.odo.team.domain.exception;

public class AlreadyRequestedException extends RuntimeException {

	public AlreadyRequestedException() {
		super("대기 중인 입장 요청이 있다");
	}

}
