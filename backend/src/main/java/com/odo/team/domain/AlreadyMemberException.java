package com.odo.team.domain;

public class AlreadyMemberException extends RuntimeException {

	public AlreadyMemberException() {
		super("이미 팀원이다");
	}

}
