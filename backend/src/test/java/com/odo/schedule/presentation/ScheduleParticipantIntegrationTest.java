package com.odo.schedule.presentation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.odo.schedule.presentation.ScheduleRequests.DEADLINE;
import static com.odo.schedule.presentation.ScheduleRequests.END;
import static com.odo.schedule.presentation.ScheduleRequests.body;
import static com.odo.schedule.presentation.ScheduleRequests.instant;
import static com.odo.schedule.presentation.ScheduleRequests.scheduleBody;
import static com.odo.schedule.presentation.ScheduleRequests.votingBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 참여자 목록과 팀장의 참여자 상태 변경. 03_Schedule_spec.md의 "참여자 (통합)"과 API 명세의 참여자 부분.
 * 투표 값은 투표 기능(PR C)이 없어서 DB에 바로 넣는다.
 */
@IntegrationTest
class ScheduleParticipantIntegrationTest {

	private static final String STATUS_CHANGED = "SCHEDULE_PARTICIPANT_STATUS_CHANGED";

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

	private long memberB;

	private long outsider;

	private long teamId;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new ScheduleRequests(mockMvc, accessTokenIssuer, jdbc);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		outsider = TestAccounts.create(jdbc, "외부인", clock.instant());
		teamId = api.createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, memberA, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberB, 26, clock.instant());
	}

	private long createVoting() throws Exception {
		return api.createScheduleId(leader, teamId, votingBody("자유 연습"));
	}

	private long createNonVoting() throws Exception {
		return api.createScheduleId(leader, teamId, scheduleBody("공연팀 연습"));
	}

	private static Object field(ResultActions result, long accountId, String name) throws Exception {
		List<Object> values = JsonPath.read(body(result), "$[?(@.accountId == " + accountId + ")]." + name);
		return values.getFirst();
	}

	@Nested
	@DisplayName("목록")
	class ListParticipants {

		@Test
		@DisplayName("팀원 전원의 이름, 기수, 상태, 투표가 온다. 제외된 사람도 볼 수 있다")
		void list() throws Exception {
			Map<String, Object> body = votingBody("자유 연습");
			body.put("requiredAccountIds", List.of(memberA));
			long scheduleId = api.createScheduleId(leader, teamId, body);
			api.setVote(scheduleId, memberB, "YES", clock.instant());

			ResultActions result = api.participants(memberB, scheduleId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)));

			assertThat(field(result, memberA, "name")).isEqualTo("김지수");
			assertThat(field(result, memberA, "generation")).isEqualTo(25);
			assertThat(field(result, memberA, "status")).isEqualTo("REQUIRED");
			assertThat(field(result, memberA, "vote")).isNull();
			assertThat(field(result, memberB, "status")).isEqualTo("OPTIONAL");
			assertThat(field(result, memberB, "vote")).isEqualTo("YES");
			assertThat(field(result, leader, "status")).isEqualTo("OPTIONAL");

			long nonVoting = createNonVoting();
			api.participants(memberB, nonVoting).andExpect(status().isOk());
		}

		@Test
		@DisplayName("팀원이 아니거나 없는 일정이면 404 SCHEDULE_NOT_FOUND다")
		void notVisible() throws Exception {
			long scheduleId = createVoting();

			api.participants(outsider, scheduleId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
			api.participants(memberA, 999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("상태 변경")
	class ChangeStatus {

		@Test
		@DisplayName("투표 없는 일정에서는 필수와 제외로 바꾸고, 바뀐 참여자 항목을 받는다")
		void nonVoting() throws Exception {
			long scheduleId = createNonVoting();

			api.changeStatus(leader, scheduleId, memberA, "REQUIRED")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(memberA))
				.andExpect(jsonPath("$.name").value("김지수"))
				.andExpect(jsonPath("$.generation").value(25))
				.andExpect(jsonPath("$.status").value("REQUIRED"))
				.andExpect(jsonPath("$.vote").value(nullValue()));
			api.changeStatus(leader, scheduleId, memberA, "EXCLUDED").andExpect(status().isOk());

			assertThat(api.statusOf(scheduleId, memberA)).isEqualTo("EXCLUDED");
		}

		@Test
		@DisplayName("투표 없는 일정에 선택, 투표 일정에 제외를 주면 400 INVALID_STATUS이고 바뀌지 않는다")
		void invalidStatusForSchedule() throws Exception {
			long nonVoting = createNonVoting();
			long voting = createVoting();

			api.changeStatus(leader, nonVoting, memberA, "OPTIONAL")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STATUS"));
			api.changeStatus(leader, voting, memberA, "EXCLUDED")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_STATUS"));

			assertThat(api.statusOf(nonVoting, memberA)).isEqualTo("EXCLUDED");
			assertThat(api.statusOf(voting, memberA)).isEqualTo("OPTIONAL");
		}

		@Test
		@DisplayName("상태 값이 없거나 정해진 값이 아니면 400 INVALID_REQUEST다")
		void unreadableStatus() throws Exception {
			long scheduleId = createVoting();

			api.changeStatus(leader, scheduleId, memberA, "MAYBE")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			api.changeStatus(leader, scheduleId, memberA, null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("참여로 투표한 선택 참여자를 필수로 바꾸면 투표가 지워지고 자리가 하나 난다")
		void optionalYesToRequired() throws Exception {
			Map<String, Object> body = votingBody("자유 연습");
			body.put("capacity", 2);
			long scheduleId = api.createScheduleId(leader, teamId, body);
			api.setVote(scheduleId, memberA, "YES", clock.instant());
			api.setVote(scheduleId, memberB, "YES", clock.instant());

			api.changeStatus(leader, scheduleId, memberA, "REQUIRED")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vote").value(nullValue()));

			assertThat(api.voteOf(scheduleId, memberA)).isNull();
			api.schedule(leader, scheduleId)
				.andExpect(jsonPath("$.confirmedCount").value(1))
				.andExpect(jsonPath("$.requiredCount").value(1));
		}

		@Test
		@DisplayName("필수를 선택으로 바꾸면 미투표로 시작한다")
		void requiredToOptional() throws Exception {
			Map<String, Object> body = votingBody("자유 연습");
			body.put("requiredAccountIds", List.of(memberA));
			long scheduleId = api.createScheduleId(leader, teamId, body);

			api.changeStatus(leader, scheduleId, memberA, "OPTIONAL")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPTIONAL"))
				.andExpect(jsonPath("$.vote").value(nullValue()));
		}

		@Test
		@DisplayName("같은 상태로 바꾸면 아무것도 바꾸지 않고 200이며, 알림 이벤트도 남지 않는다")
		void sameStatus() throws Exception {
			long scheduleId = createVoting();
			api.setVote(scheduleId, memberA, "NO", clock.instant());
			jdbc.update("DELETE FROM notification_event");

			api.changeStatus(leader, scheduleId, memberA, "OPTIONAL")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vote").value("NO"));

			assertThat(api.voteOf(scheduleId, memberA)).isEqualTo("NO");
			assertThat(api.notified(STATUS_CHANGED)).isEmpty();
		}

		@Test
		@DisplayName("팀장이 아니면 403, 팀원이 아니면 404 SCHEDULE_NOT_FOUND, 일정의 참여자가 아니면 404 PARTICIPANT_NOT_FOUND다")
		void permissionAndTarget() throws Exception {
			long scheduleId = createVoting();

			api.changeStatus(memberA, scheduleId, memberB, "REQUIRED")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.changeStatus(outsider, scheduleId, memberB, "REQUIRED")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
			api.changeStatus(leader, scheduleId, outsider, "REQUIRED")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_FOUND"));

			assertThat(api.statusOf(scheduleId, memberB)).isEqualTo("OPTIONAL");
		}

		@Test
		@DisplayName("투표 마감 뒤에도 종료 전까지는 바꿀 수 있고, 종료 시간이 지나면 409 SCHEDULE_ENDED다")
		void untilEnd() throws Exception {
			long scheduleId = createVoting();

			clock.set(instant(DEADLINE).plus(Duration.ofMinutes(10)));
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());

			clock.set(instant(END));
			api.changeStatus(leader, scheduleId, memberB, "REQUIRED")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SCHEDULE_ENDED"));
			assertThat(api.statusOf(scheduleId, memberB)).isEqualTo("OPTIONAL");
		}

		@Test
		@DisplayName("상태가 바뀐 사람에게만 알림 이벤트가 남고, 팀장이 자기 상태를 바꾸면 남지 않는다")
		void notification() throws Exception {
			long scheduleId = createVoting();
			jdbc.update("DELETE FROM notification_event");

			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());
			api.changeStatus(leader, scheduleId, leader, "REQUIRED").andExpect(status().isOk());

			assertThat(api.notified(STATUS_CHANGED)).containsExactly(memberA);
		}

	}

}
