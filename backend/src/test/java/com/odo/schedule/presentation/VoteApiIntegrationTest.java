package com.odo.schedule.presentation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.odo.auth.application.AccessTokenIssuer;
import com.odo.schedule.application.VoteProcessor;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.odo.schedule.presentation.ScheduleRequests.body;
import static com.odo.schedule.presentation.ScheduleRequests.instantAt;
import static com.odo.schedule.presentation.ScheduleRequests.votingBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 투표 접수와 결과 조회 API. 03_Schedule_spec.md의 "투표", "투표 결과", 설계의 "접수 (도착부터 202까지)".
 * 작업자는 꺼 두고(odo.vote.worker-enabled=false) processDue()를 직접 부른다. 시간은 MutableClock으로 옮긴다.
 * 처리 규칙(순서, 정원, 마감, 무효)은 VoteProcessingIntegrationTest가 확인한다.
 */
@IntegrationTest
class VoteApiIntegrationTest {

	private static final Duration W = Duration.ofSeconds(4);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	VoteProcessor voteProcessor;

	@Autowired
	ApplicationContext context;

	private ScheduleRequests api;

	private long leader;

	private long memberA;

	private long memberB;

	private long outsider;

	private long teamId;

	private long scheduleId;

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
		scheduleId = api.createScheduleId(leader, teamId, votingBody("자유 연습"));
	}

	private Map<String, Object> requestRow(long requestId) {
		return jdbc.queryForMap("SELECT * FROM vote_request WHERE id = ?", requestId);
	}

	private static Instant instantOf(Object timestamp) {
		return ((Timestamp) timestamp).toInstant();
	}

	@Nested
	@DisplayName("접수")
	class Intake {

		@Test
		@DisplayName("투표하면 202와 Location, PENDING이고, 요청 행에 µs로 자른 도착 시각과 받은 서버 이름이 저장된다")
		void accepted() throws Exception {
			clock.set(Instant.parse("2026-11-20T09:00:00.123456789Z"));

			ResultActions result = api.vote(memberA, scheduleId, "YES")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"));

			long requestId = ((Number) JsonPath.read(body(result), "$.requestId")).longValue();
			result.andExpect(header().string("Location", "/api/vote-requests/" + requestId));
			Map<String, Object> row = requestRow(requestId);
			assertThat(((Number) row.get("schedule_id")).longValue()).isEqualTo(scheduleId);
			assertThat(((Number) row.get("account_id")).longValue()).isEqualTo(memberA);
			assertThat(row.get("choice")).isEqualTo("YES");
			assertThat(row.get("status")).isEqualTo("PENDING");
			assertThat(row.get("reason")).isNull();
			assertThat(row.get("reordered")).isEqualTo(false);
			assertThat(row.get("processed_at")).isNull();
			assertThat(instantOf(row.get("arrived_at"))).isEqualTo(Instant.parse("2026-11-20T09:00:00.123456Z"));
			assertThat((String) row.get("received_by")).isNotBlank().hasSizeLessThanOrEqualTo(63);
		}

		@Test
		@DisplayName("처리되기 전에는 참여자 행의 투표가 바뀌지 않는다")
		void notAppliedBeforeProcessing() throws Exception {
			api.voteRequestId(memberA, scheduleId, "YES");

			assertThat(api.voteOf(scheduleId, memberA)).isNull();
		}

		@Test
		@DisplayName("접수할 때는 팀원인지, 일정이 있는지 확인하지 않아 팀원이 아닌 사람과 없는 일정에도 202다")
		void acceptsWithoutChecks() throws Exception {
			api.vote(outsider, scheduleId, "YES").andExpect(status().isAccepted());
			api.vote(memberA, scheduleId + 1000, "NO").andExpect(status().isAccepted());

			assertThat(api.countVoteRequests()).isEqualTo(2);
		}

		@ParameterizedTest
		@NullSource
		@ValueSource(strings = { "", "MAYBE", "yes" })
		@DisplayName("choice가 없거나 YES, NO가 아니면 400 INVALID_REQUEST이고 요청이 저장되지 않는다")
		void invalidChoice(String choice) throws Exception {
			api.vote(memberA, scheduleId, choice)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			assertThat(api.countVoteRequests()).isZero();
		}

		@Test
		@DisplayName("로그인하지 않으면 401이고 요청이 저장되지 않는다")
		void unauthenticated() throws Exception {
			mockMvc.perform(post("/api/schedules/{id}/votes", scheduleId).contentType("application/json")
				.content("{\"choice\":\"YES\"}")).andExpect(status().isUnauthorized());

			assertThat(api.countVoteRequests()).isZero();
		}

		@Test
		@DisplayName("도착 시각을 찍는 필터는 인증(Spring Security) 필터보다 먼저 실행되도록 등록된다")
		void arrivalFilterBeforeSecurity() {
			int arrivalOrder = context.getBeansOfType(FilterRegistrationBean.class)
				.values()
				.stream()
				.filter(registration -> registration.getFilter() instanceof VoteArrivalFilter)
				.mapToInt(AbstractFilterRegistrationBean::getOrder)
				.findFirst()
				.orElseThrow();
			int securityOrder = context
				.getBean("securityFilterChainRegistration", DelegatingFilterProxyRegistrationBean.class)
				.getOrder();

			assertThat(arrivalOrder).isLessThan(securityOrder);
		}

	}

	@Nested
	@DisplayName("결과 조회")
	class Result {

		@Test
		@DisplayName("처리 전에는 PENDING이고, 이유와 처리 시각이 없다")
		void pending() throws Exception {
			Instant arrivedAt = clock.instant();
			long requestId = api.voteRequestId(memberA, scheduleId, "YES");

			ResultActions result = api.voteResult(memberA, requestId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").value(requestId))
				.andExpect(jsonPath("$.scheduleId").value(scheduleId))
				.andExpect(jsonPath("$.choice").value("YES"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.reason").value(nullValue()))
				.andExpect(jsonPath("$.processedAt").value(nullValue()));
			assertThat(instantAt(result, "$.arrivedAt")).isEqualTo(arrivedAt);
		}

		@Test
		@DisplayName("처리하면 CONFIRMED와 처리 시각이 보이고, 일정 상세에 내 투표와 참여 인원이 반영된다")
		void confirmed() throws Exception {
			Instant arrivedAt = clock.instant();
			long requestId = api.voteRequestId(memberA, scheduleId, "YES");
			Instant processedAt = arrivedAt.plus(W);
			clock.set(processedAt);

			voteProcessor.processDue();

			ResultActions result = api.voteResult(memberA, requestId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.reason").value(nullValue()));
			assertThat(instantAt(result, "$.processedAt")).isEqualTo(processedAt);
			api.schedule(memberA, scheduleId)
				.andExpect(jsonPath("$.myVote").value("YES"))
				.andExpect(jsonPath("$.confirmedCount").value(1));
		}

		@Test
		@DisplayName("투표가 반영되면 참여자 행의 voted_at은 처리한 시각이다")
		void votedAtIsProcessedAt() throws Exception {
			long requestId = api.voteRequestId(memberA, scheduleId, "NO");
			Instant processedAt = clock.instant().plus(W).plusMillis(150);
			clock.set(processedAt);

			voteProcessor.processDue();

			Instant votedAt = jdbc.queryForObject(
					"SELECT voted_at FROM schedule_participant WHERE schedule_id = ? AND account_id = ?", Timestamp.class,
					scheduleId, memberA)
				.toInstant();
			assertThat(votedAt).isEqualTo(processedAt);
			assertThat(instantOf(requestRow(requestId).get("processed_at"))).isEqualTo(processedAt);
		}

		@Test
		@DisplayName("다른 사람의 요청이나 없는 요청은 404 VOTE_REQUEST_NOT_FOUND다")
		void notFound() throws Exception {
			long requestId = api.voteRequestId(memberA, scheduleId, "YES");

			api.voteResult(memberB, requestId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("VOTE_REQUEST_NOT_FOUND"));
			api.voteResult(leader, requestId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("VOTE_REQUEST_NOT_FOUND"));
			api.voteResult(memberA, requestId + 1000)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("VOTE_REQUEST_NOT_FOUND"));
		}

		@Test
		@DisplayName("로그인하지 않으면 401이다")
		void unauthenticated() throws Exception {
			long requestId = api.voteRequestId(memberA, scheduleId, "YES");

			mockMvc.perform(get("/api/vote-requests/{requestId}", requestId)).andExpect(status().isUnauthorized());
		}

	}

}
