package com.odo.schedule.domain.exception;

public class DuplicateMemberException extends RuntimeException {

	public DuplicateMemberException() {
		super("필수 참여자에 같은 사람이 두 번 있다");
	}

}
