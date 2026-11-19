package com.odo.team.domain.rule;

import com.odo.team.domain.exception.InvalidGenerationException;

/**
 * 기수 규칙. 비어 있거나 1~999.
 * TeamMember와 JoinRequest가 같이 쓰는 규칙이라 엔티티 밖에 둔다.
 */
public final class GenerationRule {

	public static final int MIN = 1;

	public static final int MAX = 999;

	private GenerationRule() {
	}

	public static boolean isValidGeneration(Integer generation) {
		return generation == null || (generation >= MIN && generation <= MAX);
	}

	/** 규칙에 맞지 않으면 InvalidGenerationException. */
	public static void validateGeneration(Integer generation) {
		if (!isValidGeneration(generation)) {
			throw new InvalidGenerationException();
		}
	}

}
