package com.odo.team.domain.exception;

public class NotTeamLeaderException extends RuntimeException {

	public NotTeamLeaderException() {
		super("팀장만 할 수 있다");
	}

}
