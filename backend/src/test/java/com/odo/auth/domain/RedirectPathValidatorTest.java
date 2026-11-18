package com.odo.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectPathValidatorTest {

	@ParameterizedTest
	@ValueSource(strings = { "/", "/invite/abc", "/invite/aB3-_.~", "/teams/1/performances/2" })
	@DisplayName("우리 사이트 안의 경로는 그대로 쓴다")
	void allowed(String path) {
		assertThat(RedirectPathValidator.sanitize(path)).isEqualTo(path);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://evil.com", "http://evil.com/invite", "//evil.com", "/\\evil.com", "\\\\evil.com",
			"javascript:alert(1)", "/a?b=c", "/a#b", "/a:b", "invite/abc", "/초대", "/a b", "/a%2F%2Fevil.com" })
	@DisplayName("다른 사이트로 갈 수 있거나 허용하지 않은 문자가 든 값은 / 가 된다")
	void rejected(String path) {
		assertThat(RedirectPathValidator.sanitize(path)).isEqualTo("/");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@DisplayName("값이 없으면 / 가 된다")
	void empty(String path) {
		assertThat(RedirectPathValidator.sanitize(path)).isEqualTo("/");
	}

	@Test
	@DisplayName("200자는 통과하고 201자는 / 가 된다")
	void length() {
		String path200 = "/" + "a".repeat(199);
		String path201 = "/" + "a".repeat(200);

		assertThat(RedirectPathValidator.sanitize(path200)).isEqualTo(path200);
		assertThat(RedirectPathValidator.sanitize(path201)).isEqualTo("/");
	}

}
