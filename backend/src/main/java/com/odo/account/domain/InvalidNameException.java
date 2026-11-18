package com.odo.account.domain;

public class InvalidNameException extends RuntimeException {

	public InvalidNameException() {
		super("이름 규칙에 맞지 않는다");
	}

}
