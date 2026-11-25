package com.odo.schedule.domain.exception;

public class InvalidRentalFeeException extends RuntimeException {

	public InvalidRentalFeeException() {
		super("대관비는 0 이상 2,147,483,647 이하의 정수여야 한다");
	}

}
