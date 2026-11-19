package com.odo.team.domain.rule;

import com.odo.team.domain.exception.InvalidGenerationException;

/**
 * 기수 규칙. 비어 있거나 1~999.
 */
public final class Generation {

	public static final int MIN = 1;

	public static final int MAX = 999;

	private Generation() {
	}

	public static boolean isValid(Integer generation) {
		return generation == null || (generation >= MIN && generation <= MAX);
	}

	/** 규칙에 맞지 않으면 InvalidGenerationException. */
	public static void validate(Integer generation) {
		if (!isValid(generation)) {
			throw new InvalidGenerationException();
		}
	}

}
