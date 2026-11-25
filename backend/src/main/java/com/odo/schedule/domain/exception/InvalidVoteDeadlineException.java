package com.odo.schedule.domain.exception;

public class InvalidVoteDeadlineException extends RuntimeException {

	public InvalidVoteDeadlineException() {
		super("투표 마감 기한은 현재 이후의 10분 단위이고 시작 시간과 같거나 그 전이어야 한다");
	}

}
