package com.odo.team.domain.exception;

public class InvalidTeamNameException extends RuntimeException {

	public InvalidTeamNameException() {
		super("팀 이름 규칙에 맞지 않는다");
	}

}
