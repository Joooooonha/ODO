package com.odo.team.domain;

public class InvalidGenerationException extends RuntimeException {

	public InvalidGenerationException() {
		super("기수는 비어 있거나 1~999다");
	}

}
