package com.odo.performance.domain.exception;

public class DuplicateMemberException extends RuntimeException {

	public DuplicateMemberException() {
		super("명단에 같은 사람이 두 번 있다");
	}

}
