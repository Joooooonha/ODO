package com.odo.performance.domain.exception;

public class NotTeamMemberException extends RuntimeException {

	public NotTeamMemberException() {
		super("팀원이 아닌 사람은 명단에 넣을 수 없다");
	}

}
