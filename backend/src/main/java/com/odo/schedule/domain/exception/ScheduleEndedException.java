package com.odo.schedule.domain.exception;

public class ScheduleEndedException extends RuntimeException {

	public ScheduleEndedException() {
		super("종료된 일정이다");
	}

}
