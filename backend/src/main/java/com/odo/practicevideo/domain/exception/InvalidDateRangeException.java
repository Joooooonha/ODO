package com.odo.practicevideo.domain.exception;

public class InvalidDateRangeException extends RuntimeException {

	public InvalidDateRangeException() {
		super("검색 시작일이 종료일보다 늦다");
	}

}
