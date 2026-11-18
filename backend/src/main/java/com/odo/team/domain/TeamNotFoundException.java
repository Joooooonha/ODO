package com.odo.team.domain;

public class TeamNotFoundException extends RuntimeException {

	public TeamNotFoundException() {
		super("팀이 없거나 팀원이 아니다");
	}

}
