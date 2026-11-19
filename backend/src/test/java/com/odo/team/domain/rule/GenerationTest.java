package com.odo.team.domain.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTest {

	@ParameterizedTest
	@NullSource
	@ValueSource(ints = { 1, 24, 999 })
	@DisplayName("비어 있거나 1~999면 통과한다")
	void allowed(Integer generation) {
		assertThat(Generation.isValid(generation)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 1000, -1 })
	@DisplayName("0, 1000, 음수는 거부한다")
	void rejected(Integer generation) {
		assertThat(Generation.isValid(generation)).isFalse();
	}

}
