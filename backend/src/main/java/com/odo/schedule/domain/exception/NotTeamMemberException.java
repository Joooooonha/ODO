package com.odo.schedule.domain.exception;

public class NotTeamMemberException extends RuntimeException {

	public NotTeamMemberException() {
		super("필수 참여자에 그 팀의 팀원이 아닌 사람이 있다");
	}

}
