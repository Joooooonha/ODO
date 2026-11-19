package com.odo.team.domain.rule;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamDescriptionRuleTest {

	@Test
	@DisplayName("null과 빈 문자열은 소개 없음(null)이고 규칙에 맞는다")
	void empty() {
		assertThat(TeamDescriptionRule.normalize(null)).isNull();
		assertThat(TeamDescriptionRule.normalize("")).isNull();
		assertThat(TeamDescriptionRule.isValid(null)).isTrue();
		assertThat(TeamDescriptionRule.isValid("")).isTrue();
	}

	@Test
	@DisplayName("이모지, 기호, 줄바꿈을 쓸 수 있다")
	void anyCharacters() {
		assertThat(TeamDescriptionRule.isValid("🎉 매주 화목 연습!\n신입 환영 (경력 무관) #힙합")).isTrue();
	}

	@Test
	@DisplayName("100자는 통과하고 101자는 거부한다")
	void length() {
		assertThat(TeamDescriptionRule.isValid("가".repeat(100))).isTrue();
		assertThat(TeamDescriptionRule.isValid("가".repeat(101))).isFalse();
	}

	@Test
	@DisplayName("앞뒤 공백도 글자 수에 들어가고, 저장할 때 빼지 않는다")
	void surroundingSpaces() {
		assertThat(TeamDescriptionRule.isValid(" " + "가".repeat(99))).isTrue();
		assertThat(TeamDescriptionRule.isValid(" " + "가".repeat(100))).isFalse();
		assertThat(TeamDescriptionRule.normalize("  환영합니다  ")).isEqualTo("  환영합니다  ");
	}

	@Test
	@DisplayName("글자 수는 코드 포인트로 센다. 이모지 100개는 통과하고 101개는 거부한다")
	void countsCodePoints() {
		assertThat(TeamDescriptionRule.isValid("😀".repeat(100))).isTrue();
		assertThat(TeamDescriptionRule.isValid("😀".repeat(101))).isFalse();
	}

	@Test
	@DisplayName("저장할 값은 NFC로 합친 값이다")
	void normalize() {
		String nfd = Normalizer.normalize("환영합니다", Normalizer.Form.NFD);
		assertThat(TeamDescriptionRule.normalize(nfd)).isEqualTo("환영합니다");
	}

}
