package com.odo.account.domain;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NameRuleTest {

	@Nested
	@DisplayName("이름 검사")
	class Validate {

		@ParameterizedTest
		@ValueSource(strings = { "홍", "홍길동", "남궁민수", "Jiwoo", "JIWOO", "jiwoo", "박Jun", "가나다라마바사아자차카타파하가나다라마바" })
		@DisplayName("한글 완성 글자와 영문 대소문자로 된 1~20자 이름은 통과한다")
		void validNames(String name) {
			assertThat(NameRule.isValid(name)).isTrue();
		}

		@Test
		@DisplayName("21자 이름은 거부된다")
		void tooLong() {
			assertThat(NameRule.isValid("가나다라마바사아자차카타파하가나다라마바사")).isFalse();
		}

		@ParameterizedTest
		@NullAndEmptySource
		@DisplayName("빈 이름은 거부된다")
		void empty(String name) {
			assertThat(NameRule.isValid(name)).isFalse();
		}

		@ParameterizedTest
		@ValueSource(strings = { "홍길동1", "홍길동!", "홍_길동", "홍길동🐥", "홍 길동", " 홍길동", "홍길동 ", "ㅋㅋㅋ", "홍길도ㅇ", "ㅏ", "洪吉童", "やまだ", "Émile" })
		@DisplayName("숫자, 특수문자, 이모지, 공백, 한글 자음과 모음, 한글과 영문 외의 글자가 들어 있으면 거부된다")
		void invalidCharacters(String name) {
			assertThat(NameRule.isValid(name)).isFalse();
		}

		@Test
		@DisplayName("NFD로 분해된 한글 이름은 정규화되어 통과한다")
		void nfdHangul() {
			String nfd = Normalizer.normalize("홍길동", Normalizer.Form.NFD);
			assertThat(nfd).isNotEqualTo("홍길동");

			assertThat(NameRule.isValid(nfd)).isTrue();
			assertThat(NameRule.normalize(nfd)).isEqualTo("홍길동");
		}

	}

	@Nested
	@DisplayName("닉네임 정리")
	class SanitizeNickname {

		@Test
		@DisplayName("규칙에 맞는 닉네임은 그대로 쓴다")
		void validNickname() {
			assertThat(NameRule.sanitizeNickname("박준하")).isEqualTo("박준하");
		}

		@Test
		@DisplayName("이모지, 공백, 숫자, 특수문자, 자음과 모음은 빠진다")
		void removesInvalidCharacters() {
			assertThat(NameRule.sanitizeNickname("🐥뽀짝 지수_99ㅋㅋ!")).isEqualTo("뽀짝지수");
		}

		@Test
		@DisplayName("20자를 넘으면 앞에서부터 20자로 자른다")
		void truncates() {
			assertThat(NameRule.sanitizeNickname("가나다라마바사아자차카타파하가나다라마바사아자차카"))
				.isEqualTo("가나다라마바사아자차카타파하가나다라마바");
		}

		@Test
		@DisplayName("쓸 수 없는 글자를 뺀 뒤 20자를 센다")
		void truncatesAfterRemoving() {
			assertThat(NameRule.sanitizeNickname("🐥🐥🐥 가나다라마바사아자차카타파하가나다라마바사"))
				.isEqualTo("가나다라마바사아자차카타파하가나다라마바");
		}

		@Test
		@DisplayName("NFD로 분해된 닉네임은 정규화해서 정리한다")
		void nfdNickname() {
			assertThat(NameRule.sanitizeNickname(Normalizer.normalize("홍길동", Normalizer.Form.NFD))).isEqualTo("홍길동");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "🐥🐥", "1234", "ㅋㅋㅋ", "!!!", "   " })
		@DisplayName("쓸 수 없는 글자만 있거나 닉네임이 없으면 ODO사용자가 된다")
		void fallback(String nickname) {
			assertThat(NameRule.sanitizeNickname(nickname)).isEqualTo("ODO사용자");
		}

		@Test
		@DisplayName("정리한 닉네임은 항상 이름 규칙을 통과한다")
		void resultIsAlwaysValid() {
			for (String nickname : new String[] { "🐥뽀짝 지수", "ㅋㅋ", "Jiwoo Park!", "가나다라마바사아자차카타파하가나다라마바사아" }) {
				assertThat(NameRule.isValid(NameRule.sanitizeNickname(nickname))).as(nickname).isTrue();
			}
		}

	}

}
