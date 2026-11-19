package com.odo.team.domain.exception;

public class InviteNotFoundException extends RuntimeException {

	public InviteNotFoundException() {
		super("초대 코드가 없거나 무효가 되었다");
	}

}
