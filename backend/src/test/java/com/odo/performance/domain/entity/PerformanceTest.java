package com.odo.performance.domain.entity;

import java.text.Normalizer;
import java.time.Instant;

import com.odo.performance.domain.exception.InvalidPerformanceDescriptionException;
import com.odo.performance.domain.exception.InvalidPerformanceNameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공연 이름과 설명 규칙은 Performance의 private 메서드라 Performance.createPerformance를 거쳐 확인한다.
 * 06_Performance_spec.md의 "규칙 (단위)".
 */
class PerformanceTest {

	private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

	private static final long TEAM_ID = 7L;

	private static final String VALID_NAME = "2026 정기공연";

	private static Performance createWithName(String name) {
		return Performance.createPerformance(TEAM_ID, name, null, NOW);
	}

	private static Performance createWithDescription(String description) {
		return Performance.createPerformance(TEAM_ID, VALID_NAME, description, NOW);
	}

	@Nested
	@DisplayName("공연 이름")
	class Name {

		@ParameterizedTest
		@ValueSource(strings = { "2026 정기공연", "Winter Showcase", "블랙홀 24기 공연", "A", "123" })
		@DisplayName("한글, 영문, 숫자, 공백으로 된 이름은 통과한다")
		void allowed(String name) {
			assertThatCode(() -> createWithName(name)).doesNotThrowAnyException();
		}

		@ParameterizedTest
		@ValueSource(strings = { "정기공연!", "🎵공연", "ㅋㅋ공연", "공연_이름", "공연\t이름", "공연\n이름", "コンサート" })
		@DisplayName("특수문자, 이모지, 한글 자모, 공백이 아닌 빈칸, 한글과 영문 외의 글자가 들어 있으면 거부한다")
		void rejected(String name) {
			assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidPerformanceNameException.class);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("비어 있거나 공백만 있으면 거부한다")
		void blank(String name) {
			assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidPerformanceNameException.class);
		}

		@Test
		@DisplayName("앞뒤 공백을 뺀 뒤 100자는 통과하고 101자는 거부한다")
		void length() {
			assertThatCode(() -> createWithName("가".repeat(100))).doesNotThrowAnyException();
			assertThatCode(() -> createWithName("  " + "가".repeat(100) + "  ")).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithName("가".repeat(101)))
				.isInstanceOf(InvalidPerformanceNameException.class);
		}

		@Test
		@DisplayName("저장할 값은 앞뒤 공백을 빼고 NFC로 합친 값이다. NFD로 온 한글도 통과한다")
		void normalize() {
			assertThat(createWithName("  2026 정기공연  ").getName()).isEqualTo("2026 정기공연");
			String nfd = Normalizer.normalize("정기공연", Normalizer.Form.NFD);
			assertThat(createWithName(nfd).getName()).isEqualTo("정기공연");
		}

		@Test
		@DisplayName("공연은 만든 팀을 가리킨다")
		void team() {
			assertThat(createWithName(VALID_NAME).getTeamId()).isEqualTo(TEAM_ID);
		}

	}

	@Nested
	@DisplayName("공연 설명")
	class Description {

		@Test
		@DisplayName("null과 빈 문자열은 설명 없음(null)이고 규칙에 맞는다")
		void empty() {
			assertThat(createWithDescription(null).getDescription()).isNull();
			assertThat(createWithDescription("").getDescription()).isNull();
		}

		@Test
		@DisplayName("이모지, 기호, 줄바꿈을 쓸 수 있다")
		void anyCharacters() {
			String description = "🎉 12월 20일 학생회관!\n리허설은 18일 (대강당) #정기공연";
			assertThat(createWithDescription(description).getDescription()).isEqualTo(description);
		}

		@Test
		@DisplayName("100자는 통과하고 101자는 거부한다")
		void length() {
			assertThatCode(() -> createWithDescription("가".repeat(100))).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithDescription("가".repeat(101)))
				.isInstanceOf(InvalidPerformanceDescriptionException.class);
		}

		@Test
		@DisplayName("앞뒤 공백도 글자 수에 들어가고, 저장할 때 빼지 않는다")
		void surroundingSpaces() {
			assertThatCode(() -> createWithDescription(" " + "가".repeat(99))).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithDescription(" " + "가".repeat(100)))
				.isInstanceOf(InvalidPerformanceDescriptionException.class);
			assertThat(createWithDescription("  학생회관  ").getDescription()).isEqualTo("  학생회관  ");
		}

		@Test
		@DisplayName("글자 수는 코드 포인트로 센다. 이모지 100개는 통과하고 101개는 거부한다")
		void countsCodePoints() {
			assertThatCode(() -> createWithDescription("😀".repeat(100))).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithDescription("😀".repeat(101)))
				.isInstanceOf(InvalidPerformanceDescriptionException.class);
		}

		@Test
		@DisplayName("저장할 값은 NFC로 합친 값이다")
		void normalize() {
			String nfd = Normalizer.normalize("학생회관", Normalizer.Form.NFD);
			assertThat(createWithDescription(nfd).getDescription()).isEqualTo("학생회관");
		}

	}

}
