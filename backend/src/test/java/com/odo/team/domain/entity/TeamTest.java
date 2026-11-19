package com.odo.team.domain.entity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.odo.auth.domain.RedirectPathValidator;
import com.odo.team.domain.exception.InvalidTeamDescriptionException;
import com.odo.team.domain.exception.InvalidTeamNameException;
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
 * 팀 이름, 소개 문구, 초대 코드 규칙은 Team의 private 메서드라 Team.createTeam을 거쳐 확인한다.
 */
class TeamTest {

	private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

	private static final String VALID_NAME = "블랙홀";

	private static Team createWithName(String name) {
		return Team.createTeam(name, null, NOW);
	}

	private static Team createWithDescription(String description) {
		return Team.createTeam(VALID_NAME, description, NOW);
	}

	@Nested
	@DisplayName("팀 이름")
	class Name {

		@ParameterizedTest
		@ValueSource(strings = { "블랙홀", "Black Hole", "블랙홀 24기", "A", "123" })
		@DisplayName("한글, 영문, 숫자, 공백으로 된 이름은 통과한다")
		void allowed(String name) {
			assertThatCode(() -> createWithName(name)).doesNotThrowAnyException();
		}

		@ParameterizedTest
		@ValueSource(strings = { "블랙홀!", "🐥팀", "ㅋㅋ팀", "팀_이름", "팀\t이름", "팀\n이름", "チーム" })
		@DisplayName("특수문자, 이모지, 한글 자모, 공백이 아닌 빈칸, 한글과 영문 외의 글자가 들어 있으면 거부한다")
		void rejected(String name) {
			assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidTeamNameException.class);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("비어 있거나 공백만 있으면 거부한다")
		void blank(String name) {
			assertThatThrownBy(() -> createWithName(name)).isInstanceOf(InvalidTeamNameException.class);
		}

		@Test
		@DisplayName("앞뒤 공백을 뺀 뒤 30자는 통과하고 31자는 거부한다")
		void length() {
			assertThatCode(() -> createWithName("가".repeat(30))).doesNotThrowAnyException();
			assertThatCode(() -> createWithName("  " + "가".repeat(30) + "  ")).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithName("가".repeat(31))).isInstanceOf(InvalidTeamNameException.class);
		}

		@Test
		@DisplayName("저장할 값은 앞뒤 공백을 빼고 NFC로 합친 값이다")
		void normalize() {
			assertThat(createWithName("  블랙홀 24기  ").getName()).isEqualTo("블랙홀 24기");
			String nfd = Normalizer.normalize("블랙홀", Normalizer.Form.NFD);
			assertThat(createWithName(nfd).getName()).isEqualTo("블랙홀");
		}

	}

	@Nested
	@DisplayName("소개 문구")
	class Description {

		@Test
		@DisplayName("null과 빈 문자열은 소개 없음(null)이고 규칙에 맞는다")
		void empty() {
			assertThat(createWithDescription(null).getDescription()).isNull();
			assertThat(createWithDescription("").getDescription()).isNull();
		}

		@Test
		@DisplayName("이모지, 기호, 줄바꿈을 쓸 수 있다")
		void anyCharacters() {
			String description = "🎉 매주 화목 연습!\n신입 환영 (경력 무관) #힙합";
			assertThat(createWithDescription(description).getDescription()).isEqualTo(description);
		}

		@Test
		@DisplayName("100자는 통과하고 101자는 거부한다")
		void length() {
			assertThatCode(() -> createWithDescription("가".repeat(100))).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithDescription("가".repeat(101)))
				.isInstanceOf(InvalidTeamDescriptionException.class);
		}

		@Test
		@DisplayName("앞뒤 공백도 글자 수에 들어가고, 저장할 때 빼지 않는다")
		void surroundingSpaces() {
			assertThatCode(() -> createWithDescription(" " + "가".repeat(99))).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithDescription(" " + "가".repeat(100)))
				.isInstanceOf(InvalidTeamDescriptionException.class);
			assertThat(createWithDescription("  환영합니다  ").getDescription()).isEqualTo("  환영합니다  ");
		}

		@Test
		@DisplayName("글자 수는 코드 포인트로 센다. 이모지 100개는 통과하고 101개는 거부한다")
		void countsCodePoints() {
			assertThatCode(() -> createWithDescription("😀".repeat(100))).doesNotThrowAnyException();
			assertThatThrownBy(() -> createWithDescription("😀".repeat(101)))
				.isInstanceOf(InvalidTeamDescriptionException.class);
		}

		@Test
		@DisplayName("저장할 값은 NFC로 합친 값이다")
		void normalize() {
			String nfd = Normalizer.normalize("환영합니다", Normalizer.Form.NFD);
			assertThat(createWithDescription(nfd).getDescription()).isEqualTo("환영합니다");
		}

	}

	@Nested
	@DisplayName("초대 코드")
	class InviteCode {

		@Test
		@DisplayName("초대 코드는 base64url 글자로 된 22자다")
		void format() {
			assertThat(createWithName(VALID_NAME).getInviteCode()).matches("^[A-Za-z0-9_-]{22}$");
		}

		@Test
		@DisplayName("만들 때마다 다른 코드가 나온다")
		void random() {
			Set<String> codes = new HashSet<>();
			for (int i = 0; i < 1000; i++) {
				codes.add(createWithName(VALID_NAME).getInviteCode());
			}
			assertThat(codes).hasSize(1000);
		}

		@Test
		@DisplayName("초대 링크 경로는 로그인 뒤 돌아갈 주소 검사를 그대로 통과한다")
		void passesRedirectCheck() {
			for (int i = 0; i < 100; i++) {
				String path = "/invite/" + createWithName(VALID_NAME).getInviteCode();
				assertThat(RedirectPathValidator.safePathOrDefault(path)).isEqualTo(path);
			}
		}

	}

}
