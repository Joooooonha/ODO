package com.odo.performance.domain.exception;

public class LeaderNotInMembersException extends RuntimeException {

	public LeaderNotInMembersException() {
		super("공연 명단에 팀장이 있어야 한다");
	}

}
