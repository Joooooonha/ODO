package com.odo.performance.domain.exception;

public class EmptyMembersException extends RuntimeException {

	public EmptyMembersException() {
		super("추가할 사람이 없다");
	}

}
