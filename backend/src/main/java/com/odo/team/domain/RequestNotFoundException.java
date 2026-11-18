package com.odo.team.domain;

public class RequestNotFoundException extends RuntimeException {

	public RequestNotFoundException() {
		super("입장 요청이 없다");
	}

}
