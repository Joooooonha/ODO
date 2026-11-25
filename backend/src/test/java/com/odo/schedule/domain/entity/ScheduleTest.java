package com.odo.schedule.domain.entity;

import java.text.Normalizer;
import java.time.Instant;

import com.odo.schedule.domain.exception.InvalidCapacityException;
import com.odo.schedule.domain.exception.InvalidDurationException;
import com.odo.schedule.domain.exception.InvalidScheduleMemoException;
import com.odo.schedule.domain.exception.InvalidScheduleTitleException;
import com.odo.schedule.domain.exception.InvalidStartAtException;
import com.odo.schedule.domain.exception.InvalidVoteDeadlineException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 일정 입력 규칙. 03_Schedule_spec.md의 "일정", "투표 마감 기한", "선착순 정원", "입력 검증과 글자 규칙", "규칙 (단위)".
 * 각 규칙은 검증한 뒤 저장할 값을 돌려준다.
 */
class ScheduleTest {

	/** 2026-11-20 18:00 (한국 시간). */
	private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

	@Nested
	@DisplayName("제목")
	class Title {

		@Test
		@DisplayName("앞뒤 공백을 빼고 NFC로 합친 값을 돌려준다")
		void normalized() {
			assertThat(Schedule.requireValidTitle("  자유 연습  ")).isEqualTo("자유 연습");
			assertThat(Schedule.requireValidTitle(Normalizer.normalize("연습", Normalizer.Form.NFD))).isEqualTo("연습");
		}

		@ParameterizedTest
		@ValueSource(strings = { "Free Practice", "自主練習", "フリー練習", "#1 연습 (B팀)" })
		@DisplayName("한글과 영문 외의 언어, 숫자, 기호도 쓸 수 있다")
		void otherLanguages(String title) {
			assertThat(Schedule.requireValidTitle(title)).isEqualTo(title);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("없거나 공백만 있으면 거부한다")
		void blank(String title) {
			assertThatThrownBy(() -> Schedule.requireValidTitle(title)).isInstanceOf(InvalidScheduleTitleException.class);
		}

		@Test
		@DisplayName("앞뒤 공백을 뺀 뒤 50자는 통과하고 51자는 거부한다. 길이는 코드 포인트로 센다")
		void length() {
			assertThat(Schedule.requireValidTitle(" " + "가".repeat(50) + " ")).hasSize(50);
			assertThatThrownBy(() -> Schedule.requireValidTitle("가".repeat(51)))
				.isInstanceOf(InvalidScheduleTitleException.class);
		}

		@ParameterizedTest
		@ValueSource(strings = { "연습 😀", "Love ♥️", "자유\n연습", "자유\t연습" })
		@DisplayName("이모지, 줄바꿈, 탭이 있으면 거부한다")
		void forbidden(String title) {
			assertThatThrownBy(() -> Schedule.requireValidTitle(title)).isInstanceOf(InvalidScheduleTitleException.class);
		}

	}

	@Nested
	@DisplayName("메모")
	class Memo {

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   " })
		@DisplayName("없거나 공백만 있으면 메모 없음(null)이다")
		void empty(String memo) {
			assertThat(Schedule.requireValidMemo(memo)).isNull();
		}

		@Test
		@DisplayName("줄바꿈은 쓸 수 있고, 앞뒤 공백을 뺀다")
		void multiLine() {
			assertThat(Schedule.requireValidMemo("  물 챙기기\n늦으면 연락  ")).isEqualTo("물 챙기기\n늦으면 연락");
		}

		@Test
		@DisplayName("300자는 통과하고 301자는 거부한다")
		void length() {
			assertThat(Schedule.requireValidMemo("가".repeat(300))).hasSize(300);
			assertThatThrownBy(() -> Schedule.requireValidMemo("가".repeat(301)))
				.isInstanceOf(InvalidScheduleMemoException.class);
		}

		@Test
		@DisplayName("이모지가 있으면 거부한다")
		void emoji() {
			assertThatThrownBy(() -> Schedule.requireValidMemo("물 챙기기 💧"))
				.isInstanceOf(InvalidScheduleMemoException.class);
		}

	}

	@Nested
	@DisplayName("시작 시간")
	class StartAt {

		@Test
		@DisplayName("현재 이후의 10분 단위 시각은 통과한다")
		void valid() {
			Instant startAt = Instant.parse("2026-11-25T10:10:00Z");

			assertThat(Schedule.requireValidStartAt(startAt, NOW)).isEqualTo(startAt);
		}

		@ParameterizedTest
		@ValueSource(strings = { "2026-11-25T10:05:00Z", "2026-11-25T10:10:30Z", "2026-11-25T10:10:00.000001Z" })
		@DisplayName("분이 10의 배수가 아니거나 초, 나노초가 0이 아니면 거부한다")
		void notTenMinuteUnit(String startAt) {
			assertThatThrownBy(() -> Schedule.requireValidStartAt(Instant.parse(startAt), NOW))
				.isInstanceOf(InvalidStartAtException.class);
		}

		@Test
		@DisplayName("현재 이전이면 거부한다")
		void past() {
			assertThatThrownBy(() -> Schedule.requireValidStartAt(Instant.parse("2026-11-20T08:50:00Z"), NOW))
				.isInstanceOf(InvalidStartAtException.class);
		}

	}

	@Nested
	@DisplayName("연습 시간")
	class Duration {

		@ParameterizedTest
		@ValueSource(ints = { 30, 60, 90, 1440 })
		@DisplayName("30분 단위로 30분 이상 1,440분 이하는 통과한다")
		void valid(int minutes) {
			assertThat(Schedule.requireValidDuration(minutes)).isEqualTo(minutes);
		}

		@ParameterizedTest
		@ValueSource(ints = { 0, 20, 29, 45, 1441, 1470, -30 })
		@DisplayName("30분 단위가 아니거나 30분 미만, 1,440분 초과면 거부한다")
		void invalid(int minutes) {
			assertThatThrownBy(() -> Schedule.requireValidDuration(minutes)).isInstanceOf(InvalidDurationException.class);
		}

	}

	@Nested
	@DisplayName("투표 마감 기한")
	class VoteDeadline {

		private final Instant startAt = Instant.parse("2026-11-25T10:00:00Z");

		@Test
		@DisplayName("기본값은 만든 시점부터 24시간 뒤를 10분 단위로 올린 시각이다(15:32에 만들면 다음 날 15:40)")
		void defaultRoundsUp() {
			Instant createdAt = Instant.parse("2026-11-20T06:32:00Z");

			assertThat(Schedule.defaultVoteDeadline(createdAt, startAt)).isEqualTo(Instant.parse("2026-11-21T06:40:00Z"));
		}

		@Test
		@DisplayName("만든 시각이 이미 10분 단위면 그대로 24시간 뒤이고, 1µs라도 넘으면 다음 10분이다")
		void defaultOnBoundary() {
			assertThat(Schedule.defaultVoteDeadline(Instant.parse("2026-11-20T06:30:00Z"), startAt))
				.isEqualTo(Instant.parse("2026-11-21T06:30:00Z"));
			assertThat(Schedule.defaultVoteDeadline(Instant.parse("2026-11-20T06:30:00.000001Z"), startAt))
				.isEqualTo(Instant.parse("2026-11-21T06:40:00Z"));
		}

		@Test
		@DisplayName("연습 시작이 기본값보다 이르면 연습 시작 시간이 기본값이다")
		void defaultCappedByStart() {
			Instant earlyStart = Instant.parse("2026-11-21T03:00:00Z");

			assertThat(Schedule.defaultVoteDeadline(NOW, earlyStart)).isEqualTo(earlyStart);
		}

		@Test
		@DisplayName("10분 단위이고 현재 이후이며 시작 시간과 같거나 그 전이면 통과한다")
		void valid() {
			assertThat(Schedule.requireValidVoteDeadline(Instant.parse("2026-11-24T10:00:00Z"), NOW, startAt))
				.isEqualTo(Instant.parse("2026-11-24T10:00:00Z"));
			assertThat(Schedule.requireValidVoteDeadline(startAt, NOW, startAt)).isEqualTo(startAt);
		}

		@ParameterizedTest
		@ValueSource(strings = { "2026-11-24T10:05:00Z", "2026-11-20T08:50:00Z", "2026-11-25T10:10:00Z" })
		@DisplayName("10분 단위가 아니거나, 현재 이전이거나, 시작 시간보다 늦으면 거부한다")
		void invalid(String deadline) {
			assertThatThrownBy(() -> Schedule.requireValidVoteDeadline(Instant.parse(deadline), NOW, startAt))
				.isInstanceOf(InvalidVoteDeadlineException.class);
		}

	}

	@Nested
	@DisplayName("정원")
	class Capacity {

		@Test
		@DisplayName("없으면 정원 없음이고, 1 이상은 통과한다")
		void valid() {
			assertThat(Schedule.requireValidCapacity(null)).isNull();
			assertThat(Schedule.requireValidCapacity(1)).isEqualTo(1);
			assertThat(Schedule.requireValidCapacity(500)).isEqualTo(500);
		}

		@ParameterizedTest
		@ValueSource(ints = { 0, -1 })
		@DisplayName("1 미만이면 거부한다")
		void invalid(int capacity) {
			assertThatThrownBy(() -> Schedule.requireValidCapacity(capacity)).isInstanceOf(InvalidCapacityException.class);
		}

	}

}
