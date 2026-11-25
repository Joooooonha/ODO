package com.odo.schedule.domain.exception;

public class CapacityBelowConfirmedException extends RuntimeException {

	public CapacityBelowConfirmedException() {
		super("정원을 지금 참여 인원보다 작게 정할 수 없다");
	}

}
