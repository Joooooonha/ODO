package com.odo.team.domain;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TeamNameRuleTest {

	@ParameterizedTest
	@ValueSource(strings = { "블랙홀", "Black Hole", "블랙홀 24기", "A", "123" })
	@DisplayName("한글, 영문, 숫자, 공백으로 된 이름은 통과한다")
	void allowed(String name) {
		assertThat(TeamNameRule.isValid(name)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "블랙홀!", "🐥팀", "ㅋㅋ팀", "팀_이름", "팀\t이름", "팀\n이름", "チーム" })
	@DisplayName("특수문자, 이모지, 한글 자모, 공백이 아닌 빈칸, 한글과 영문 외의 글자가 들어 있으면 거부한다")
	void rejected(String name) {
		assertThat(TeamNameRule.isValid(name)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	@DisplayName("비어 있거나 공백만 있으면 거부한다")
	void blank(String name) {
		assertThat(TeamNameRule.isValid(name)).isFalse();
	}

	@Test
	@DisplayName("앞뒤 공백을 뺀 뒤 30자는 통과하고 31자는 거부한다")
	void length() {
		assertThat(TeamNameRule.isValid("가".repeat(30))).isTrue();
		assertThat(TeamNameRule.isValid("  " + "가".repeat(30) + "  ")).isTrue();
		assertThat(TeamNameRule.isValid("가".repeat(31))).isFalse();
	}

	@Test
	@DisplayName("저장할 값은 앞뒤 공백을 빼고 NFC로 합친 값이다")
	void normalize() {
		assertThat(TeamNameRule.normalize("  블랙홀 24기  ")).isEqualTo("블랙홀 24기");
		String nfd = Normalizer.normalize("블랙홀", Normalizer.Form.NFD);
		assertThat(TeamNameRule.isValid(nfd)).isTrue();
		assertThat(TeamNameRule.normalize(nfd)).isEqualTo("블랙홀");
	}

}
