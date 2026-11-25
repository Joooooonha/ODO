package com.odo.schedule.domain.entity;

import com.odo.schedule.domain.exception.InvalidPracticeRoomAddressException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomMemoException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomNameException;
import com.odo.schedule.domain.exception.InvalidPracticeRoomPasswordException;
import com.odo.schedule.domain.exception.InvalidRentalFeeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연습실 항목 규칙. 이름만 필수다. 03_Schedule_spec.md의 "장소와 연습실 목록", "규칙 (단위)".
 * 각 규칙은 검증한 뒤 저장할 값을 돌려준다. 선택 항목이 비어 있으면 null이다.
 */
class PracticeRoomTest {

	@Nested
	@DisplayName("이름")
	class Name {

		@Test
		@DisplayName("앞뒤 공백을 빼고, 다른 언어의 글자도 쓸 수 있다")
		void valid() {
			assertThat(PracticeRoom.requireValidName("  합정 A룸  ")).isEqualTo("합정 A룸");
			assertThat(PracticeRoom.requireValidName("スタジオ 3")).isEqualTo("スタジオ 3");
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("없거나 공백만 있으면 거부한다")
		void blank(String name) {
			assertThatThrownBy(() -> PracticeRoom.requireValidName(name))
				.isInstanceOf(InvalidPracticeRoomNameException.class);
		}

		@Test
		@DisplayName("50자는 통과하고 51자는 거부한다")
		void length() {
			assertThat(PracticeRoom.requireValidName("가".repeat(50))).hasSize(50);
			assertThatThrownBy(() -> PracticeRoom.requireValidName("가".repeat(51)))
				.isInstanceOf(InvalidPracticeRoomNameException.class);
		}

		@ParameterizedTest
		@ValueSource(strings = { "합정 🎵", "합정\nA룸" })
		@DisplayName("이모지나 줄바꿈이 있으면 거부한다")
		void forbidden(String name) {
			assertThatThrownBy(() -> PracticeRoom.requireValidName(name))
				.isInstanceOf(InvalidPracticeRoomNameException.class);
		}

	}

	@Nested
	@DisplayName("주소와 비밀번호")
	class AddressAndPassword {

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "  " })
		@DisplayName("비어 있으면 null이다")
		void empty(String value) {
			assertThat(PracticeRoom.requireValidAddress(value)).isNull();
			assertThat(PracticeRoom.requireValidPassword(value)).isNull();
		}

		@Test
		@DisplayName("앞뒤 공백을 빼고 50자까지 받는다. 비밀번호의 기호와 가운데 공백은 그대로 둔다")
		void valid() {
			assertThat(PracticeRoom.requireValidAddress(" 서울 마포구 양화로 1 ")).isEqualTo("서울 마포구 양화로 1");
			assertThat(PracticeRoom.requireValidPassword(" 1234 *# ")).isEqualTo("1234 *#");
			assertThat(PracticeRoom.requireValidAddress("가".repeat(50))).hasSize(50);
			assertThat(PracticeRoom.requireValidPassword("1".repeat(50))).hasSize(50);
		}

		@Test
		@DisplayName("51자는 거부한다")
		void tooLong() {
			assertThatThrownBy(() -> PracticeRoom.requireValidAddress("가".repeat(51)))
				.isInstanceOf(InvalidPracticeRoomAddressException.class);
			assertThatThrownBy(() -> PracticeRoom.requireValidPassword("1".repeat(51)))
				.isInstanceOf(InvalidPracticeRoomPasswordException.class);
		}

		@Test
		@DisplayName("이모지나 줄바꿈이 있으면 거부한다")
		void forbidden() {
			assertThatThrownBy(() -> PracticeRoom.requireValidAddress("서울\n마포구"))
				.isInstanceOf(InvalidPracticeRoomAddressException.class);
			assertThatThrownBy(() -> PracticeRoom.requireValidPassword("1234🔑"))
				.isInstanceOf(InvalidPracticeRoomPasswordException.class);
		}

	}

	@Nested
	@DisplayName("메모")
	class Memo {

		@Test
		@DisplayName("줄바꿈을 쓸 수 있고 300자까지 받는다. 비어 있으면 null이다")
		void valid() {
			assertThat(PracticeRoom.requireValidMemo("정수기 있음\n화장실은 2층")).isEqualTo("정수기 있음\n화장실은 2층");
			assertThat(PracticeRoom.requireValidMemo("가".repeat(300))).hasSize(300);
			assertThat(PracticeRoom.requireValidMemo(" ")).isNull();
		}

		@Test
		@DisplayName("301자이거나 이모지가 있으면 거부한다")
		void invalid() {
			assertThatThrownBy(() -> PracticeRoom.requireValidMemo("가".repeat(301)))
				.isInstanceOf(InvalidPracticeRoomMemoException.class);
			assertThatThrownBy(() -> PracticeRoom.requireValidMemo("정수기 💧"))
				.isInstanceOf(InvalidPracticeRoomMemoException.class);
		}

	}

	@Nested
	@DisplayName("대관비")
	class RentalFee {

		@Test
		@DisplayName("없으면 null이고, 0 이상 2,147,483,647 이하의 정수를 받는다")
		void valid() {
			assertThat(PracticeRoom.requireValidRentalFee(null)).isNull();
			assertThat(PracticeRoom.requireValidRentalFee(0L)).isZero();
			assertThat(PracticeRoom.requireValidRentalFee(15_000L)).isEqualTo(15_000);
			assertThat(PracticeRoom.requireValidRentalFee(2_147_483_647L)).isEqualTo(Integer.MAX_VALUE);
		}

		@ParameterizedTest
		@ValueSource(longs = { -1L, 2_147_483_648L })
		@DisplayName("0 미만이거나 저장 타입의 상한을 넘으면 거부한다")
		void invalid(long fee) {
			assertThatThrownBy(() -> PracticeRoom.requireValidRentalFee(fee))
				.isInstanceOf(InvalidRentalFeeException.class);
		}

	}

}
