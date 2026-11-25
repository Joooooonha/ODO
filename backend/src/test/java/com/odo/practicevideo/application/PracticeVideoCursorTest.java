package com.odo.practicevideo.application;

import java.time.Instant;
import java.util.Base64;

import com.odo.practicevideo.domain.exception.InvalidCursorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 목록 커서. 08_PracticeVideo_spec.md의 "목록과 날짜 검색", "조회와 기록", "규칙 (단위)".
 */
class PracticeVideoCursorTest {

	@Test
	@DisplayName("만든 커서를 읽으면 같은 (업로드 시각, ID)이다. 시각은 µs까지 남는다")
	void roundTrip() {
		Instant uploadedAt = Instant.parse("2026-11-26T10:00:00.123456Z");
		String cursor = PracticeVideoCursor.encode(uploadedAt, 31L);

		PracticeVideoCursor decoded = PracticeVideoCursor.decode(cursor);

		assertThat(decoded.uploadedAt()).isEqualTo(uploadedAt);
		assertThat(decoded.id()).isEqualTo(31L);
	}

	@Test
	@DisplayName("URL에 그대로 넣을 수 있는 글자만 쓴다(+, /, = 없음)")
	void urlSafe() {
		String cursor = PracticeVideoCursor.encode(Instant.parse("2026-11-26T10:00:00Z"), Long.MAX_VALUE);

		assertThat(cursor).matches("[A-Za-z0-9_-]+");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "!!!", "abc", "MTIz" })
	@DisplayName("base64가 아니거나 형식이 틀리면 InvalidCursorException이다")
	void invalid(String cursor) {
		assertThatThrownBy(() -> PracticeVideoCursor.decode(cursor)).isInstanceOf(InvalidCursorException.class);
	}

	@Test
	@DisplayName("base64이지만 시각이나 ID가 숫자가 아니면 InvalidCursorException이다")
	void notNumbers() {
		String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString("abc:def".getBytes());

		assertThatThrownBy(() -> PracticeVideoCursor.decode(cursor)).isInstanceOf(InvalidCursorException.class);
	}

}
