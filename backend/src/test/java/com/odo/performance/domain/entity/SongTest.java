package com.odo.performance.domain.entity;

import java.text.Normalizer;
import java.time.Instant;

import com.odo.performance.domain.exception.InvalidSongNameException;
import com.odo.performance.domain.exception.NotSongDirectorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 곡 이름 규칙은 Song의 private 메서드라 Song.createSong을 거쳐 확인한다.
 * 06_Performance_spec.md의 "규칙 (단위)"와 "곡 이름의 글자 규칙".
 */
class SongTest {

	private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

	private static final long PERFORMANCE_ID = 3L;

	private static final long DIRECTOR_ACCOUNT_ID = 42L;

	private static Song createWithName(String name) {
		return Song.createSong(PERFORMANCE_ID, DIRECTOR_ACCOUNT_ID, name, NOW);
	}

	@ParameterizedTest
	@ValueSource(strings = { "Hype Boy (Remix)", "Ditto 2", "#1 Song*", "アイドル", "夜に駆ける", "사랑♥", "Love ©", "★☆♪",
			"Don't Stop - Live ver." })
	@DisplayName("숫자, 공백, 특수문자, 한글과 영문 외의 언어, 이모지 표시가 없는 기호는 통과한다")
	void allowed(String name) {
		assertThatCode(() -> createWithName(name)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "😀", "Hype Boy 🎵", "Love ♥️", "1️⃣ Song", "Good 👍🏻",
			"Korea 🇰🇷", "Family 👨‍👩‍👧" })
	@DisplayName("이모지, 이모지 표시(U+FE0F)가 붙은 기호, 키캡, 피부색, 국기, ZWJ로 이은 이모지는 거부한다")
	void emojiRejected(String name) {
		assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidSongNameException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "Hype\nBoy", "Hype\tBoy", "Hype\rBoy", "Hype​Boy", "Hype Boy" })
	@DisplayName("줄바꿈, 탭, 서식 문자, 줄 구분 문자가 들어 있으면 거부한다")
	void controlRejected(String name) {
		assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidSongNameException.class);
	}

	@Test
	@DisplayName("이모지 표시 없이 쓴 ❤(U+2764)와 ☺(U+263A)는 글자로 보고 통과한다. spec에 적은 알려진 한계다")
	void textStyleHeartPasses() {
		assertThatCode(() -> createWithName("Love ❤")).doesNotThrowAnyException();
		assertThatCode(() -> createWithName("Smile ☺")).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	@DisplayName("비어 있거나 공백만 있으면 거부한다")
	void blank(String name) {
		assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidSongNameException.class);
	}

	@Test
	@DisplayName("앞뒤 공백을 뺀 뒤 100자는 통과하고 101자는 거부한다")
	void length() {
		assertThatCode(() -> createWithName("가".repeat(100))).doesNotThrowAnyException();
		assertThatCode(() -> createWithName("  " + "가".repeat(100) + "  ")).doesNotThrowAnyException();
		assertThatThrownBy(() -> createWithName("가".repeat(101))).isInstanceOf(InvalidSongNameException.class);
	}

	@Test
	@DisplayName("글자 수는 코드 포인트로 센다. 보조 평면 글자(𝄞) 100개는 통과하고 101개는 거부한다")
	void countsCodePoints() {
		String gClef = "𝄞";
		assertThatCode(() -> createWithName(gClef.repeat(100))).doesNotThrowAnyException();
		assertThatThrownBy(() -> createWithName(gClef.repeat(101))).isInstanceOf(InvalidSongNameException.class);
	}

	@Test
	@DisplayName("저장할 값은 앞뒤 공백을 빼고 NFC로 합친 값이다")
	void normalize() {
		assertThat(createWithName("  Hype Boy (Remix)  ").getName()).isEqualTo("Hype Boy (Remix)");
		String nfd = Normalizer.normalize("사랑의 노래", Normalizer.Form.NFD);
		assertThat(createWithName(nfd).getName()).isEqualTo("사랑의 노래");
	}

	@Test
	@DisplayName("곡은 공연과 만든 담당 디렉터를 가리키고, 삭제되지 않은 상태로 만들어진다")
	void owner() {
		Song song = createWithName("Ditto");
		assertThat(song.getPerformanceId()).isEqualTo(PERFORMANCE_ID);
		assertThat(song.getDirectorAccountId()).isEqualTo(DIRECTOR_ACCOUNT_ID);
		assertThat(song.getDeletedAt()).isNull();
	}

	@Test
	@DisplayName("만든 담당 디렉터면 requireDirectedBy가 통과한다")
	void directedBy() {
		Song song = createWithName("Ditto");
		assertThatCode(() -> song.requireDirectedBy(DIRECTOR_ACCOUNT_ID)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("담당 디렉터가 아니면 requireDirectedBy가 NotSongDirectorException을 던진다")
	void notDirectedBy() {
		Song song = createWithName("Ditto");
		assertThatThrownBy(() -> song.requireDirectedBy(DIRECTOR_ACCOUNT_ID + 1))
			.isInstanceOf(NotSongDirectorException.class);
	}

}
