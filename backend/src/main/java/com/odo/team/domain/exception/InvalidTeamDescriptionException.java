package com.odo.team.domain.exception;

public class InvalidTeamDescriptionException extends RuntimeException {

	public InvalidTeamDescriptionException() {
		super("팀 소개 문구 규칙에 맞지 않는다");
	}

}
