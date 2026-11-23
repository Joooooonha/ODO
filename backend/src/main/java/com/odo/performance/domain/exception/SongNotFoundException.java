package com.odo.performance.domain.exception;

public class SongNotFoundException extends RuntimeException {

	public SongNotFoundException() {
		super("곡이 없다");
	}

}
