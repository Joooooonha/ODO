package com.odo.common.text;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자가 입력해 다른 사람에게 보이는 글자의 공통 규칙.
 * 03_Schedule_spec.md의 "입력 검증과 글자 규칙"과 "규칙 (단위)". 막는 글자는 곡 이름 규칙과 같다.
 */
class UserTextRuleTest {

	@Nested
	@DisplayName("normalize")
	class Normalize {

		@Test
		@DisplayName("앞뒤 공백을 뺀다")
		void strip() {
			assertThat(UserTextRule.normalize("  화요일 연습  ")).isEqualTo("화요일 연습");
		}

		@Test
		@DisplayName("NFD로 온 한글을 NFC로 합친다")
		void nfc() {
			String decomposed = Normalizer.normalize("연습", Normalizer.Form.NFD);

			assertThat(decomposed).isNotEqualTo("연습");
			assertThat(UserTextRule.normalize(decomposed)).isEqualTo("연습");
		}

		@Test
		@DisplayName("null은 null로 돌려준다. 선택 항목이 비어 있는 경우다")
		void nullStaysNull() {
			assertThat(UserTextRule.normalize(null)).isNull();
		}

	}

	@Nested
	@DisplayName("한 줄 글자")
	class SingleLine {

		@ParameterizedTest
		@ValueSource(strings = { "화요일 연습", "Practice 1", "アイドル", "練習室", "مرحبا", "Привет", "#1 (A)", "사랑♥", "★☆♪",
				"Love ©" })
		@DisplayName("한글과 영문 외의 언어, 숫자, 기호, 이모지 표시가 없는 기호는 통과한다")
		void allowed(String text) {
			assertThat(UserTextRule.containsForbiddenCharForSingleLine(text)).isFalse();
		}

		@ParameterizedTest
		@ValueSource(strings = { "😀", "연습 🎵", "Love ♥️", "1️⃣ 연습", "Good 👍🏻", "Korea 🇰🇷", "Family 👨‍👩‍👧" })
		@DisplayName("이모지, 이모지 표시(U+FE0F)가 붙은 기호, 키캡, 피부색, 국기, ZWJ로 이은 이모지는 막는다")
		void emoji(String text) {
			assertThat(UserTextRule.containsForbiddenCharForSingleLine(text)).isTrue();
		}

		@ParameterizedTest
		@ValueSource(strings = { "화요일\n연습", "화요일\t연습", "화요일\r연습", "화요일​연습", "화요일 연습",
				"화요일 연습", "화요일\u0007연습" })
		@DisplayName("줄바꿈, 탭, 제어 문자, 서식 문자(폭 없는 공백), 줄과 문단 구분 문자는 막는다")
		void control(String text) {
			assertThat(UserTextRule.containsForbiddenCharForSingleLine(text)).isTrue();
		}

	}

	@Nested
	@DisplayName("여러 줄 글자")
	class MultiLine {

		@ParameterizedTest
		@ValueSource(strings = { "첫 줄\n둘째 줄", "첫 줄\r\n둘째 줄", "준비물: 물, 수건\n\n늦으면 연락", "アイドル\n練習" })
		@DisplayName("줄바꿈(\\n)과 \\r\\n은 허용한다")
		void newlineAllowed(String text) {
			assertThat(UserTextRule.containsForbiddenCharForMultiLine(text)).isFalse();
		}

		@ParameterizedTest
		@ValueSource(strings = { "첫 줄\r둘째 줄", "첫 줄\t둘째 줄", "첫 줄 둘째 줄", "첫 줄​둘째 줄" })
		@DisplayName("\\n이 아닌 줄바꿈(\\r 단독, 줄 구분 문자), 탭, 서식 문자는 막는다")
		void otherControl(String text) {
			assertThat(UserTextRule.containsForbiddenCharForMultiLine(text)).isTrue();
		}

		@ParameterizedTest
		@ValueSource(strings = { "첫 줄\n😀", "Love ♥️\n둘째 줄", "Korea 🇰🇷" })
		@DisplayName("이모지는 한 줄 글자와 같이 막는다")
		void emoji(String text) {
			assertThat(UserTextRule.containsForbiddenCharForMultiLine(text)).isTrue();
		}

	}

}
