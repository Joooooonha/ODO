package com.odo.schedule.domain.exception;

public class ScheduleNotFoundException extends RuntimeException {

	public ScheduleNotFoundException() {
		super("일정이 없다");
	}

}
