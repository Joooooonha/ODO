package com.odo.schedule.domain.exception;

public class VoteRequestNotFoundException extends RuntimeException {

	public VoteRequestNotFoundException() {
		super("투표 요청이 없다");
	}

}
