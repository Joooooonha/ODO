package com.odo.practicevideo.domain.entity;

import java.text.Normalizer;

import com.odo.practicevideo.domain.exception.InvalidPracticeVideoDescriptionException;
import com.odo.practicevideo.domain.exception.InvalidPracticeVideoFileSizeException;
import com.odo.practicevideo.domain.exception.InvalidPracticeVideoTitleException;
import com.odo.practicevideo.domain.exception.UnsupportedPracticeVideoTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연습 영상 입력 규칙. 08_PracticeVideo_spec.md의 "1차의 규칙", "입력 검증과 글자 규칙", "규칙 (단위)".
 * 각 규칙은 검증한 뒤 저장할 값을 돌려준다.
 */
class PracticeVideoTest {

	private static final long ONE_GB = 1_073_741_824L;

	@Nested
	@DisplayName("제목")
	class Title {

		@Test
		@DisplayName("앞뒤 공백을 빼고 NFC로 합친 값을 돌려준다")
		void normalized() {
			assertThat(PracticeVideo.requireValidTitle("  Hype Boy 1절  ")).isEqualTo("Hype Boy 1절");
			assertThat(PracticeVideo.requireValidTitle(Normalizer.normalize("정면", Normalizer.Form.NFD)))
				.isEqualTo("정면");
		}

		@ParameterizedTest
		@ValueSource(strings = { "Hype Boy (Remix) #2", "1절 - 정면/측면", "自主練習" })
		@DisplayName("숫자와 특수문자, 다른 언어의 글자를 쓸 수 있다")
		void allowed(String title) {
			assertThat(PracticeVideo.requireValidTitle(title)).isEqualTo(title);
		}

		@Test
		@DisplayName("100자는 받고 101자는 막는다")
		void length() {
			assertThat(PracticeVideo.requireValidTitle("가".repeat(100))).hasSize(100);
			assertThatThrownBy(() -> PracticeVideo.requireValidTitle("가".repeat(101)))
				.isInstanceOf(InvalidPracticeVideoTitleException.class);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("없거나 공백만 있으면 막는다")
		void blank(String title) {
			assertThatThrownBy(() -> PracticeVideo.requireValidTitle(title))
				.isInstanceOf(InvalidPracticeVideoTitleException.class);
		}

		@ParameterizedTest
		@ValueSource(strings = { "정면 🎵", "1절\n정면", "1절\t정면" })
		@DisplayName("이모지, 줄바꿈, 탭이 있으면 막는다")
		void forbidden(String title) {
			assertThatThrownBy(() -> PracticeVideo.requireValidTitle(title))
				.isInstanceOf(InvalidPracticeVideoTitleException.class);
		}

	}

	@Nested
	@DisplayName("설명")
	class Description {

		@Test
		@DisplayName("앞뒤 공백을 뺀 값을 돌려주고, 줄바꿈을 허용한다")
		void multiLine() {
			assertThat(PracticeVideo.requireValidDescription("  0:40부터\n대형 이동  ")).isEqualTo("0:40부터\n대형 이동");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", " \n " })
		@DisplayName("없거나 공백만 있으면 설명 없음(null)이다")
		void empty(String description) {
			assertThat(PracticeVideo.requireValidDescription(description)).isNull();
		}

		@Test
		@DisplayName("100자는 받고 101자는 막는다")
		void length() {
			assertThat(PracticeVideo.requireValidDescription("가".repeat(100))).hasSize(100);
			assertThatThrownBy(() -> PracticeVideo.requireValidDescription("가".repeat(101)))
				.isInstanceOf(InvalidPracticeVideoDescriptionException.class);
		}

		@Test
		@DisplayName("이모지가 있으면 막는다")
		void emoji() {
			assertThatThrownBy(() -> PracticeVideo.requireValidDescription("대형 이동 👍"))
				.isInstanceOf(InvalidPracticeVideoDescriptionException.class);
		}

	}

	@Nested
	@DisplayName("파일 크기")
	class FileSize {

		@ParameterizedTest
		@ValueSource(longs = { 1L, ONE_GB })
		@DisplayName("1바이트부터 1GB(1,073,741,824바이트)까지 받는다")
		void allowed(long size) {
			assertThat(PracticeVideo.requireValidFileSize(size)).isEqualTo(size);
		}

		@ParameterizedTest
		@ValueSource(longs = { 0L, -1L, ONE_GB + 1 })
		@DisplayName("0 이하이거나 1GB를 넘으면 막는다")
		void rejected(long size) {
			assertThatThrownBy(() -> PracticeVideo.requireValidFileSize(size))
				.isInstanceOf(InvalidPracticeVideoFileSizeException.class);
		}

	}

	@Nested
	@DisplayName("형식")
	class ContentType {

		@ParameterizedTest
		@ValueSource(strings = { "video/mp4", "video/quicktime" })
		@DisplayName("mp4와 mov만 받는다")
		void allowed(String contentType) {
			assertThat(PracticeVideo.requireSupportedContentType(contentType)).isEqualTo(contentType);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "video/webm", "image/png", "application/octet-stream", "VIDEO/MP4", "video/mp4; codecs=avc1" })
		@DisplayName("다른 형식은 막는다")
		void rejected(String contentType) {
			assertThatThrownBy(() -> PracticeVideo.requireSupportedContentType(contentType))
				.isInstanceOf(UnsupportedPracticeVideoTypeException.class);
		}

	}

}
