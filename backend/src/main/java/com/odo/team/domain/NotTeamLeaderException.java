package com.odo.team.domain;

public class NotTeamLeaderException extends RuntimeException {

	public NotTeamLeaderException() {
		super("팀장만 할 수 있다");
	}

}
