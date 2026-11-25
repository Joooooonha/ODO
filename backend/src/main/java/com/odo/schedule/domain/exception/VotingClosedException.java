package com.odo.schedule.domain.exception;

public class VotingClosedException extends RuntimeException {

	public VotingClosedException() {
		super("투표가 마감되었다");
	}

}
