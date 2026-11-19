package com.odo.team.domain.exception;

public class AlreadyMemberException extends RuntimeException {

	public AlreadyMemberException() {
		super("이미 팀원이다");
	}

}
