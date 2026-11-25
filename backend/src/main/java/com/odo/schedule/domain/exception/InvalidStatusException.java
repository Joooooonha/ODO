package com.odo.schedule.domain.exception;

public class InvalidStatusException extends RuntimeException {

	public InvalidStatusException() {
		super("이 일정에서 쓸 수 없는 참여자 상태다");
	}

}
