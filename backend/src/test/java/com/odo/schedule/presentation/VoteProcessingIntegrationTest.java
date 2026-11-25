package com.odo.schedule.presentation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.schedule.application.VoteProcessor;
import com.odo.support.Concurrently;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static com.odo.schedule.presentation.ScheduleRequests.DEADLINE;
import static com.odo.schedule.presentation.ScheduleRequests.instant;
import static com.odo.schedule.presentation.ScheduleRequests.scheduleBody;
import static com.odo.schedule.presentation.ScheduleRequests.votingBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 작업자가 투표 요청을 처리하는 규칙. 03_Schedule_spec.md의 "참여 투표 (통합)", 설계의 "처리 (작업자)"와 판정 표,
 * "W를 넘긴 요청", "역전 기록".
 * 작업자는 꺼 두고 processDue()를 직접 부른다. 도착 시각은 투표를 보내기 전에 MutableClock을 맞춰 정한다.
 * 동시성 테스트는 10번 반복한다.
 */
@IntegrationTest
class VoteProcessingIntegrationTest {

	private static final Duration W = Duration.ofSeconds(4);

	private static final Instant T0 = MutableClock.DEFAULT_START;

	private static final int CONCURRENT_ROUNDS = 10;

	private static final int CONCURRENT_VOTERS = 8;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	MutableClock clock;

	@Autowired
	VoteProcessor voteProcessor;

	private ScheduleRequests api;

	private long leader;

	private long memberA;

	private long memberB;

	private long memberC;

	private long outsider;

	private long teamId;

	/** 동시성 테스트에서 함께 투표하는 팀원. */
	private List<Long> voters;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new ScheduleRequests(mockMvc, accessTokenIssuer, jdbc);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		memberC = TestAccounts.create(jdbc, "최민준", clock.instant());
		outsider = TestAccounts.create(jdbc, "외부인", clock.instant());
		teamId = api.createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, memberA, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberB, 26, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberC, 26, clock.instant());
		voters = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_VOTERS; i++) {
			long voter = TestAccounts.create(jdbc, "팀원 " + i, clock.instant());
			TestTeams.addMember(jdbc, teamId, voter, 27, clock.instant());
			voters.add(voter);
		}
	}

	/** 투표 일정을 만든다. 마감은 DEADLINE이다. capacity가 null이면 정원이 없다. */
	private long votingSchedule(String title, Integer capacity, List<Long> required) throws Exception {
		clock.set(T0);
		Map<String, Object> body = votingBody(title);
		if (capacity != null) {
			body.put("capacity", capacity);
		}
		if (!required.isEmpty()) {
			body.put("requiredAccountIds", required);
		}
		return api.createScheduleId(leader, teamId, body);
	}

	private long votingSchedule(Integer capacity) throws Exception {
		return votingSchedule("자유 연습", capacity, List.of());
	}

	/** 시계를 at으로 맞추고 투표를 보낸다. 도착 시각이 at이 된다. */
	private long voteAt(Instant at, long accountId, long scheduleId, String choice) throws Exception {
		clock.set(at);
		return api.voteRequestId(accountId, scheduleId, choice);
	}

	/** 시계를 now로 맞추고 작업자를 한 번 돌린다. 처리한 요청 수를 돌려준다. */
	private int processAt(Instant now) {
		clock.set(now);
		return voteProcessor.processDue();
	}

	private static Instant ms(long millis) {
		return T0.plusMillis(millis);
	}

	@Nested
	@DisplayName("도착 시각 순서")
	class Order {

		@Test
		@DisplayName("도착 시각을 거꾸로 저장해도 도착 시각 순서로 처리한다. 정원 2명에 3명이 투표하면 가장 늦게 도착한 사람이 FULL이다")
		void byArrivalTime() throws Exception {
			long scheduleId = votingSchedule(2);
			long latest = voteAt(ms(3), memberA, scheduleId, "YES");
			long earliest = voteAt(ms(1), memberB, scheduleId, "YES");
			long middle = voteAt(ms(2), memberC, scheduleId, "YES");

			int processed = processAt(ms(3).plus(W));

			assertThat(processed).isEqualTo(3);
			assertThat(api.requestStatusOf(earliest)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(middle)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(latest)).isEqualTo("FULL");
			assertThat(api.voteOf(scheduleId, memberB)).isEqualTo("YES");
			assertThat(api.voteOf(scheduleId, memberC)).isEqualTo("YES");
			assertThat(api.voteOf(scheduleId, memberA)).isNull();
			assertThat(api.reorderedOf(earliest) || api.reorderedOf(middle) || api.reorderedOf(latest)).isFalse();
		}

		@Test
		@DisplayName("도착 시각이 같으면 먼저 저장된 요청(요청 ID)이 먼저다")
		void sameArrivalByRequestId() throws Exception {
			long scheduleId = votingSchedule(1);
			long first = voteAt(T0, memberA, scheduleId, "YES");
			long second = voteAt(T0, memberB, scheduleId, "YES");

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(first)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(second)).isEqualTo("FULL");
		}

		@Test
		@DisplayName("도착 시각이 (지금 − W)보다 늦은 요청은 아직 처리하지 않고, (지금 − W)와 같아지면 처리한다")
		void waitsForW() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(T0, memberA, scheduleId, "YES");

			assertThat(processAt(T0.plus(W).minusNanos(1_000))).isZero();
			assertThat(api.requestStatusOf(requestId)).isEqualTo("PENDING");
			assertThat(api.voteOf(scheduleId, memberA)).isNull();

			assertThat(processAt(T0.plus(W))).isEqualTo(1);
			assertThat(api.requestStatusOf(requestId)).isEqualTo("CONFIRMED");
		}

		@Test
		@DisplayName("참여를 누르고 바로 불참을 누르면 도착 순서대로 반영되어 불참이다")
		void yesThenNo() throws Exception {
			long scheduleId = votingSchedule(1);
			long yes = voteAt(ms(0), memberA, scheduleId, "YES");
			long no = voteAt(ms(1), memberA, scheduleId, "NO");

			processAt(ms(1).plus(W));

			assertThat(api.requestStatusOf(yes)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(no)).isEqualTo("CONFIRMED");
			assertThat(api.voteOf(scheduleId, memberA)).isEqualTo("NO");
			assertThat(api.countYes(scheduleId)).isZero();
		}

		@Test
		@DisplayName("서로 다른 일정의 요청은 각 일정 안에서만 순서를 다툰다")
		void perSchedule() throws Exception {
			long first = votingSchedule("첫 연습", 1, List.of());
			long second = votingSchedule("둘째 연습", 1, List.of());
			long a = voteAt(ms(0), memberA, first, "YES");
			long b = voteAt(ms(1), memberB, second, "YES");

			processAt(ms(1).plus(W));

			assertThat(api.requestStatusOf(a)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(b)).isEqualTo("CONFIRMED");
		}

	}

	@Nested
	@DisplayName("W를 넘긴 요청과 역전 기록")
	class Late {

		@Test
		@DisplayName("확정된 뒤에 더 이른 도착 시각의 요청이 저장되면 처리되지만 reordered가 true이고, 확정된 결과는 바뀌지 않는다")
		void reorderedDoesNotOverturn() throws Exception {
			long scheduleId = votingSchedule(1);
			long confirmed = voteAt(ms(2_000), memberA, scheduleId, "YES");
			processAt(ms(2_000).plus(W));
			long late = voteAt(ms(1_000), memberB, scheduleId, "YES");

			processAt(ms(2_001).plus(W));

			assertThat(api.requestStatusOf(late)).isEqualTo("FULL");
			assertThat(api.reorderedOf(late)).isTrue();
			assertThat(api.requestStatusOf(confirmed)).isEqualTo("CONFIRMED");
			assertThat(api.reorderedOf(confirmed)).isFalse();
			assertThat(api.voteOf(scheduleId, memberA)).isEqualTo("YES");
			assertThat(api.voteOf(scheduleId, memberB)).isNull();
		}

		@Test
		@DisplayName("자리가 남아 있으면 늦게 저장된 요청도 거절하지 않고 확정하며, reordered가 true다")
		void lateButSeatsLeft() throws Exception {
			long scheduleId = votingSchedule(2);
			voteAt(ms(2_000), memberA, scheduleId, "YES");
			processAt(ms(2_000).plus(W));
			long late = voteAt(ms(1_000), memberB, scheduleId, "YES");

			processAt(ms(2_001).plus(W));

			assertThat(api.requestStatusOf(late)).isEqualTo("CONFIRMED");
			assertThat(api.reorderedOf(late)).isTrue();
			assertThat(api.voteOf(scheduleId, memberB)).isEqualTo("YES");
		}

		@Test
		@DisplayName("역전 판정은 지금까지 처리한 도착 시각 중 가장 늦은 것과 비교한다. 늦게 저장된 요청이 둘이어도 둘 다 reordered다")
		void comparedWithLatestProcessed() throws Exception {
			long scheduleId = votingSchedule(null);
			voteAt(ms(3_000), memberA, scheduleId, "YES");
			processAt(ms(3_000).plus(W));
			long first = voteAt(ms(1_000), memberB, scheduleId, "YES");
			long second = voteAt(ms(2_000), memberC, scheduleId, "YES");

			processAt(ms(3_001).plus(W));

			assertThat(api.reorderedOf(first)).isTrue();
			assertThat(api.reorderedOf(second)).isTrue();
			Instant last = jdbc.queryForObject("SELECT last_confirmed_arrived_at FROM schedule WHERE id = ?",
					Timestamp.class, scheduleId)
				.toInstant();
			assertThat(last).isEqualTo(ms(3_000));
		}

		@Test
		@DisplayName("확정이 아닌 결과(무효)도 늦게 저장되었으면 reordered가 true다")
		void reorderedInvalid() throws Exception {
			long scheduleId = votingSchedule("자유 연습", null, List.of(memberB));
			voteAt(ms(2_000), memberA, scheduleId, "YES");
			processAt(ms(2_000).plus(W));
			long late = voteAt(ms(1_000), memberB, scheduleId, "YES");

			processAt(ms(2_001).plus(W));

			assertThat(api.requestStatusOf(late)).isEqualTo("INVALID");
			assertThat(api.reorderedOf(late)).isTrue();
		}

	}

	@Nested
	@DisplayName("정원")
	class Capacity {

		@Test
		@DisplayName("필수 참여자는 정원에 세지 않는다")
		void requiredNotCounted() throws Exception {
			long scheduleId = votingSchedule("자유 연습", 1, List.of(memberB));
			long requestId = voteAt(T0, memberA, scheduleId, "YES");

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("CONFIRMED");
			api.schedule(memberA, scheduleId)
				.andExpect(jsonPath("$.confirmedCount").value(1))
				.andExpect(jsonPath("$.requiredCount").value(1));
		}

		@Test
		@DisplayName("정원이 없으면 참여로 투표한 사람이 모두 확정된다")
		void noCapacity() throws Exception {
			long scheduleId = votingSchedule(null);
			long a = voteAt(ms(0), memberA, scheduleId, "YES");
			long b = voteAt(ms(1), memberB, scheduleId, "YES");
			long c = voteAt(ms(2), memberC, scheduleId, "YES");

			processAt(ms(2).plus(W));

			assertThat(List.of(api.requestStatusOf(a), api.requestStatusOf(b), api.requestStatusOf(c)))
				.containsOnly("CONFIRMED");
			assertThat(api.countYes(scheduleId)).isEqualTo(3);
		}

		@Test
		@DisplayName("정원 마감 뒤 누군가 불참으로 바꾸면 자리가 나고 그 뒤 도착한 참여가 들어간다. 앞서 FULL이던 사람은 자동으로 들어가지 않는다")
		void seatFreedByNo() throws Exception {
			long scheduleId = votingSchedule(1);
			voteAt(ms(0), memberA, scheduleId, "YES");
			long rejected = voteAt(ms(1), memberB, scheduleId, "YES");
			processAt(ms(1).plus(W));
			assertThat(api.requestStatusOf(rejected)).isEqualTo("FULL");

			voteAt(ms(10_000), memberA, scheduleId, "NO");
			long next = voteAt(ms(10_001), memberC, scheduleId, "YES");
			processAt(ms(10_001).plus(W));

			assertThat(api.requestStatusOf(next)).isEqualTo("CONFIRMED");
			assertThat(api.voteOf(scheduleId, memberA)).isEqualTo("NO");
			assertThat(api.voteOf(scheduleId, memberC)).isEqualTo("YES");
			assertThat(api.voteOf(scheduleId, memberB)).isNull();
			assertThat(api.requestStatusOf(rejected)).isEqualTo("FULL");
		}

		@Test
		@DisplayName("팀장이 참여한 사람을 필수로 바꾸면 자리가 나고 그 뒤 도착한 참여가 들어간다")
		void seatFreedByLeader() throws Exception {
			long scheduleId = votingSchedule(1);
			voteAt(ms(0), memberA, scheduleId, "YES");
			processAt(ms(0).plus(W));
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());

			long next = voteAt(ms(10_000), memberB, scheduleId, "YES");
			processAt(ms(10_000).plus(W));

			assertThat(api.requestStatusOf(next)).isEqualTo("CONFIRMED");
			assertThat(api.countYes(scheduleId)).isEqualTo(1);
		}

		@Test
		@DisplayName("같은 투표를 두 번 보내도 결과는 바뀌지 않고 참여 인원도 한 명이다")
		void sameVoteTwice() throws Exception {
			long scheduleId = votingSchedule(2);
			long first = voteAt(ms(0), memberA, scheduleId, "YES");
			long second = voteAt(ms(1), memberA, scheduleId, "YES");
			long other = voteAt(ms(2), memberB, scheduleId, "YES");

			processAt(ms(2).plus(W));

			assertThat(api.requestStatusOf(first)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(second)).isEqualTo("CONFIRMED");
			assertThat(api.requestStatusOf(other)).isEqualTo("CONFIRMED");
			assertThat(api.countYes(scheduleId)).isEqualTo(2);
		}

		@Test
		@DisplayName("기다리는 동안 팀장이 정원을 늘리면 꺼낼 때의 정원으로 판단한다")
		void latestCapacity() throws Exception {
			Map<String, Object> created = votingBody("자유 연습");
			created.put("capacity", 1);
			clock.set(T0);
			long scheduleId = api.createScheduleId(leader, teamId, created);
			voteAt(ms(0), memberA, scheduleId, "YES");
			long second = voteAt(ms(1), memberB, scheduleId, "YES");
			Map<String, Object> update = ScheduleRequests.updateBody(created);
			update.put("capacity", 2);
			api.updateSchedule(leader, scheduleId, update).andExpect(status().isOk());

			processAt(ms(1).plus(W));

			assertThat(api.requestStatusOf(second)).isEqualTo("CONFIRMED");
		}

	}

	@Nested
	@DisplayName("투표 마감")
	class Deadline {

		private final Instant deadline = instant(DEADLINE);

		@Test
		@DisplayName("마감 전에 도착한 투표는 처리가 마감 뒤여도 반영된다")
		void arrivedBeforeDeadline() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(deadline.minusNanos(1_000), memberA, scheduleId, "YES");

			processAt(deadline.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("CONFIRMED");
			assertThat(api.voteOf(scheduleId, memberA)).isEqualTo("YES");
		}

		@Test
		@DisplayName("마감과 같은 시각이나 그 뒤에 도착한 투표는 INVALID VOTING_CLOSED이고 반영되지 않는다")
		void arrivedAtOrAfterDeadline() throws Exception {
			long scheduleId = votingSchedule(null);
			long atDeadline = voteAt(deadline, memberA, scheduleId, "YES");
			long afterDeadline = voteAt(deadline.plusSeconds(1), memberB, scheduleId, "NO");

			processAt(deadline.plusSeconds(1).plus(W));

			assertThat(api.requestStatusOf(atDeadline)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(atDeadline)).isEqualTo("VOTING_CLOSED");
			assertThat(api.requestStatusOf(afterDeadline)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(afterDeadline)).isEqualTo("VOTING_CLOSED");
			assertThat(api.voteOf(scheduleId, memberA)).isNull();
			assertThat(api.voteOf(scheduleId, memberB)).isNull();
		}

	}

	@Nested
	@DisplayName("무효")
	class Invalid {

		@Test
		@DisplayName("기다리는 동안 일정이 삭제되면 INVALID SCHEDULE_NOT_FOUND이고, 결과 조회로 확인할 수 있다")
		void deletedSchedule() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(T0, memberA, scheduleId, "YES");
			api.deleteSchedule(leader, scheduleId).andExpect(status().isNoContent());

			processAt(T0.plus(W));

			api.voteResult(memberA, requestId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INVALID"))
				.andExpect(jsonPath("$.reason").value("SCHEDULE_NOT_FOUND"));
		}

		@Test
		@DisplayName("없는 일정에 보낸 투표는 INVALID SCHEDULE_NOT_FOUND다")
		void missingSchedule() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(T0, memberA, scheduleId + 1000, "YES");

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(requestId)).isEqualTo("SCHEDULE_NOT_FOUND");
		}

		@Test
		@DisplayName("팀원이 아닌 사람의 투표는 일정이 있다는 것을 드러내지 않도록 INVALID SCHEDULE_NOT_FOUND다")
		void outsider() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(T0, outsider, scheduleId, "YES");

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(requestId)).isEqualTo("SCHEDULE_NOT_FOUND");
			assertThat(api.countParticipantRows(scheduleId, outsider)).isZero();
		}

		@Test
		@DisplayName("투표 없는 일정의 투표는 INVALID NOT_VOTING_SCHEDULE이다")
		void notVotingSchedule() throws Exception {
			clock.set(T0);
			long scheduleId = api.createScheduleId(leader, teamId, scheduleBody("공연팀 연습"));
			long requestId = voteAt(T0, memberA, scheduleId, "YES");

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(requestId)).isEqualTo("NOT_VOTING_SCHEDULE");
			assertThat(api.voteOf(scheduleId, memberA)).isNull();
		}

		@Test
		@DisplayName("필수 참여자의 투표는 INVALID NOT_OPTIONAL_PARTICIPANT다")
		void requiredParticipant() throws Exception {
			long scheduleId = votingSchedule("자유 연습", null, List.of(memberA));
			long requestId = voteAt(T0, memberA, scheduleId, "YES");

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(requestId)).isEqualTo("NOT_OPTIONAL_PARTICIPANT");
			assertThat(api.voteOf(scheduleId, memberA)).isNull();
		}

		@Test
		@DisplayName("기다리는 동안 팀장이 필수로 바꾸면 꺼낼 때의 상태로 판단해 INVALID NOT_OPTIONAL_PARTICIPANT다")
		void becameRequiredWhileWaiting() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(T0, memberA, scheduleId, "YES");
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());

			processAt(T0.plus(W));

			assertThat(api.requestStatusOf(requestId)).isEqualTo("INVALID");
			assertThat(api.requestReasonOf(requestId)).isEqualTo("NOT_OPTIONAL_PARTICIPANT");
		}

	}

	@Nested
	@DisplayName("잠금과 동시성")
	class Concurrency {

		@Test
		@DisplayName("다른 트랜잭션이 일정 행을 잠그고 있으면 기다리지 않고 건너뛰며, 잠금이 풀린 뒤 다음 처리에서 반영한다")
		void skipsLockedSchedule() throws Exception {
			long scheduleId = votingSchedule(null);
			long requestId = voteAt(T0, memberA, scheduleId, "YES");
			clock.set(T0.plus(W));

			try (Connection held = dataSource.getConnection()) {
				held.setAutoCommit(false);
				try (PreparedStatement lock = held.prepareStatement("SELECT id FROM schedule WHERE id = ? FOR UPDATE")) {
					lock.setLong(1, scheduleId);
					lock.executeQuery();
				}

				assertThat(voteProcessor.processDue()).isZero();
				assertThat(api.requestStatusOf(requestId)).isEqualTo("PENDING");
				held.rollback();
			}

			assertThat(voteProcessor.processDue()).isEqualTo(1);
			assertThat(api.requestStatusOf(requestId)).isEqualTo("CONFIRMED");
		}

		@Test
		@DisplayName("여러 명이 동시에 HTTP로 참여 투표를 보내도 확정 인원이 정원을 넘지 않는다")
		void concurrentVotes() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long scheduleId = votingSchedule("동시 투표 " + round, 3, List.of());
				AtomicInteger next = new AtomicInteger();

				List<MockHttpServletResponse> responses = Concurrently.run(CONCURRENT_VOTERS,
						() -> api.vote(voters.get(next.getAndIncrement()), scheduleId, "YES"));
				processAt(T0.plus(W));

				assertThat(Concurrently.statuses(responses)).containsOnly(202);
				assertThat(countByStatus(scheduleId, "CONFIRMED")).isEqualTo(3);
				assertThat(countByStatus(scheduleId, "FULL")).isEqualTo(CONCURRENT_VOTERS - 3);
				assertThat(api.countYes(scheduleId)).isEqualTo(3);
			}
		}

		@Test
		@DisplayName("processDue()를 두 스레드에서 동시에 불러도 요청은 한 번씩만 처리되고, 일정마다 도착 순서대로 정원이 찬다")
		void concurrentWorkers() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				List<Long> schedules = new ArrayList<>();
				List<List<Long>> requestsBySchedule = new ArrayList<>();
				for (int s = 0; s < 3; s++) {
					long scheduleId = votingSchedule("동시 처리 " + round + "-" + s, 2, List.of());
					List<Long> requests = new ArrayList<>();
					for (int v = 0; v < 4; v++) {
						requests.add(voteAt(ms(v), voters.get(v), scheduleId, "YES"));
					}
					schedules.add(scheduleId);
					requestsBySchedule.add(requests);
				}
				clock.set(ms(3).plus(W));

				List<Integer> processed = twoWorkersTogether();

				assertThat(processed.get(0) + processed.get(1)).isEqualTo(12);
				for (int s = 0; s < schedules.size(); s++) {
					List<Long> requests = requestsBySchedule.get(s);
					assertThat(api.requestStatusOf(requests.get(0))).isEqualTo("CONFIRMED");
					assertThat(api.requestStatusOf(requests.get(1))).isEqualTo("CONFIRMED");
					assertThat(api.requestStatusOf(requests.get(2))).isEqualTo("FULL");
					assertThat(api.requestStatusOf(requests.get(3))).isEqualTo("FULL");
					assertThat(api.countYes(schedules.get(s))).isEqualTo(2);
				}
			}
		}

		private int countByStatus(long scheduleId, String status) {
			return jdbc.queryForObject("SELECT count(*) FROM vote_request WHERE schedule_id = ? AND status = ?",
					Integer.class, scheduleId, status);
		}

		/** processDue()를 두 스레드에서 한꺼번에 부르고, 각자 처리한 요청 수를 돌려준다. */
		private List<Integer> twoWorkersTogether() throws Exception {
			ExecutorService executor = Executors.newFixedThreadPool(2);
			CountDownLatch start = new CountDownLatch(1);
			try {
				List<Future<Integer>> futures = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					futures.add(executor.submit(() -> {
						start.await();
						return voteProcessor.processDue();
					}));
				}
				start.countDown();
				return List.of(futures.get(0).get(), futures.get(1).get());
			}
			finally {
				executor.shutdownNow();
			}
		}

	}

}
