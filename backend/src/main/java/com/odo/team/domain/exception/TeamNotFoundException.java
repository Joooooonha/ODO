package com.odo.team.domain.exception;

public class TeamNotFoundException extends RuntimeException {

	public TeamNotFoundException() {
		super("팀이 없거나 팀원이 아니다");
	}

}
