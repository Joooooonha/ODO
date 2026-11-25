package com.odo.schedule.presentation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.odo.auth.application.AccessTokenIssuer;
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
import org.springframework.test.web.servlet.ResultActions;

import static com.odo.schedule.presentation.ScheduleRequests.DEADLINE;
import static com.odo.schedule.presentation.ScheduleRequests.START;
import static com.odo.schedule.presentation.ScheduleRequests.instant;
import static com.odo.schedule.presentation.ScheduleRequests.scheduleBody;
import static com.odo.schedule.presentation.ScheduleRequests.updateBody;
import static com.odo.schedule.presentation.ScheduleRequests.votingBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일정을 만든 뒤 팀에 들어온 사람. 승인되면 완료되지 않은 일정에 그 일정의 기본 상태로 들어간다.
 * 03_Schedule_spec.md의 "참여자 행과 새 팀원", "새 팀원 (통합, 동시성)". 승인은 API로 한다.
 * 동시성 테스트는 두 요청을 한꺼번에 출발시키고 10번 반복한다.
 */
@IntegrationTest
class NewMemberParticipantIntegrationTest {

	private static final int CONCURRENT_ROUNDS = 10;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private ScheduleRequests api;

	private long leader;

	private long memberA;

	private long teamId;

	private String inviteCode;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new ScheduleRequests(mockMvc, accessTokenIssuer, jdbc);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		teamId = api.createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, memberA, 25, clock.instant());
		inviteCode = api.inviteCode(leader, teamId);
	}

	/** 새 계정을 만들어 입장 요청을 보내고, 그 계정과 요청 ID를 돌려준다. */
	private long[] newApplicant(String name) throws Exception {
		long applicant = TestAccounts.create(jdbc, name, clock.instant());
		long requestId = api.requestJoinId(applicant, inviteCode);
		return new long[] { applicant, requestId };
	}

	private int notificationsFor(long accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM notification_event WHERE recipient_account_id = ?",
				Integer.class, accountId);
	}

	@Nested
	@DisplayName("기본 상태로 들어가기")
	class DefaultStatus {

		@Test
		@DisplayName("투표 일정에는 미투표 선택, 투표 없는 일정에는 제외로 들어가고, 완료된 일정에는 들어가지 않는다. 알림 이벤트는 남지 않는다")
		void joinsIncompleteSchedules() throws Exception {
			long voting = api.createScheduleId(leader, teamId, votingBody("자유 연습"));
			Map<String, Object> required = scheduleBody("공연팀 연습");
			required.put("requiredAccountIds", List.of(memberA));
			long nonVoting = api.createScheduleId(leader, teamId, required);
			Map<String, Object> soon = scheduleBody("내일 연습");
			soon.put("startAt", "2026-11-21T19:00:00+09:00");
			long done = api.createScheduleId(leader, teamId, soon);
			clock.set(instant("2026-11-22T00:00:00+09:00"));
			long[] applicant = newApplicant("최민준");

			api.approve(leader, teamId, applicant[1]).andExpect(status().isOk());

			assertThat(api.statusOf(voting, applicant[0])).isEqualTo("OPTIONAL");
			assertThat(api.voteOf(voting, applicant[0])).isNull();
			assertThat(api.statusOf(nonVoting, applicant[0])).isEqualTo("EXCLUDED");
			assertThat(api.countParticipantRows(done, applicant[0])).isZero();
			assertThat(notificationsFor(applicant[0])).isZero();
		}

		@Test
		@DisplayName("진행 중인 일정은 완료 전이므로 들어간다")
		void inProgress() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, scheduleBody("자유 연습"));
			clock.set(instant(START).plus(Duration.ofMinutes(30)));
			long[] applicant = newApplicant("최민준");

			api.approve(leader, teamId, applicant[1]).andExpect(status().isOk());

			assertThat(api.statusOf(scheduleId, applicant[0])).isEqualTo("EXCLUDED");
		}

		@Test
		@DisplayName("투표 마감 뒤에 들어와도 선택으로 들어간다")
		void afterDeadline() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, votingBody("자유 연습"));
			clock.set(instant(DEADLINE).plus(Duration.ofMinutes(10)));
			long[] applicant = newApplicant("최민준");

			api.approve(leader, teamId, applicant[1]).andExpect(status().isOk());

			assertThat(api.statusOf(scheduleId, applicant[0])).isEqualTo("OPTIONAL");
		}

		@Test
		@DisplayName("새 팀원은 이후 새로 만드는 일정에도 다른 팀원과 같이 들어간다")
		void laterSchedules() throws Exception {
			long[] applicant = newApplicant("최민준");
			api.approve(leader, teamId, applicant[1]).andExpect(status().isOk());

			long scheduleId = api.createScheduleId(leader, teamId, votingBody("자유 연습"));

			assertThat(api.countParticipantRows(scheduleId, applicant[0])).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("동시에 일어날 때")
	class Concurrency {

		@Test
		@DisplayName("일정 만들기와 승인을 동시에 보내도 새 팀원은 그 일정에 정확히 한 행으로 들어간다")
		void createAndApprove() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long[] applicant = newApplicant("새 팀원 " + round);
				String title = "동시 일정 " + round;

				List<MockHttpServletResponse> responses = together(
						() -> api.createSchedule(leader, teamId, votingBody(title)),
						() -> api.approve(leader, teamId, applicant[1]));

				assertThat(responses.get(0).getStatus()).isEqualTo(201);
				assertThat(responses.get(1).getStatus()).isEqualTo(200);
				long scheduleId = jdbc.queryForObject("SELECT id FROM schedule WHERE title = ?", Long.class, title);
				assertThat(api.countParticipantRows(scheduleId, applicant[0])).isEqualTo(1);
			}
		}

		@Test
		@DisplayName("승인과 일정 삭제를 동시에 보내도 교착이나 FK 오류 없이 둘 다 성공한다")
		void approveAndDelete() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long scheduleId = api.createScheduleId(leader, teamId, votingBody("지울 일정 " + round));
				long[] applicant = newApplicant("새 팀원 " + round);

				List<MockHttpServletResponse> responses = together(() -> api.approve(leader, teamId, applicant[1]),
						() -> api.deleteSchedule(leader, scheduleId));

				assertThat(responses.get(0).getStatus()).isEqualTo(200);
				assertThat(responses.get(1).getStatus()).isEqualTo(204);
				assertThat(api.countParticipantRows(scheduleId, applicant[0])).isZero();
			}
		}

		@Test
		@DisplayName("승인과 일정 수정을 동시에 보내도 교착 없이 둘 다 성공하고, 새 팀원은 그 일정에 한 행으로 들어간다")
		void approveAndUpdate() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				Map<String, Object> created = votingBody("고칠 일정 " + round);
				long scheduleId = api.createScheduleId(leader, teamId, created);
				long[] applicant = newApplicant("새 팀원 " + round);
				Map<String, Object> update = updateBody(created);
				update.put("memo", "수정 " + round);

				List<MockHttpServletResponse> responses = together(() -> api.approve(leader, teamId, applicant[1]),
						() -> api.updateSchedule(leader, scheduleId, update));

				assertThat(responses.get(0).getStatus()).isEqualTo(200);
				assertThat(responses.get(1).getStatus()).isEqualTo(200);
				assertThat(api.countParticipantRows(scheduleId, applicant[0])).isEqualTo(1);
			}
		}

	}

	/** 두 요청을 한꺼번에 출발시키고, 보낸 순서대로 응답을 돌려준다. */
	private static List<MockHttpServletResponse> together(Callable<ResultActions> first,
			Callable<ResultActions> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<MockHttpServletResponse> a = executor.submit(() -> {
				start.await();
				return first.call().andReturn().getResponse();
			});
			Future<MockHttpServletResponse> b = executor.submit(() -> {
				start.await();
				return second.call().andReturn().getResponse();
			});
			start.countDown();
			return List.of(a.get(), b.get());
		}
		finally {
			executor.shutdownNow();
		}
	}

}
