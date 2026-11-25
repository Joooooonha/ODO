package com.odo.schedule.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.schedule.application.VoteProcessor;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.odo.schedule.presentation.ScheduleRequests.votingBody;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업자가 다음에 언제 돌지 정한다. 03_Schedule_spec.md의 "다음 처리 시각 (통합과 단위)", 설계의 "작업자 운영".
 * 작업자는 꺼 두고 nextRunDelay()와 processDue()를 직접 부른다. 지금은 MutableClock으로 정한다.
 */
@IntegrationTest
class VoteNextRunIntegrationTest {

	private static final Duration W = Duration.ofSeconds(4);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	private static final Instant T0 = MutableClock.DEFAULT_START;

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

	private ScheduleRequests api;

	private long memberA;

	private long memberB;

	private long scheduleId;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new ScheduleRequests(mockMvc, accessTokenIssuer, jdbc);
		long leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		long teamId = api.createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, memberA, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberB, 26, clock.instant());
		clock.set(T0);
		Map<String, Object> body = votingBody("자유 연습");
		scheduleId = api.createScheduleId(leader, teamId, body);
	}

	/** 시계를 at으로 맞추고 투표를 보낸다. 도착 시각이 at이 된다. */
	private long voteAt(Instant at, long accountId) throws Exception {
		clock.set(at);
		return api.voteRequestId(accountId, scheduleId, "YES");
	}

	@Test
	@DisplayName("대기 요청이 없으면 poll-interval 뒤에 돈다")
	void noPending() {
		assertThat(voteProcessor.nextRunDelay()).isEqualTo(POLL_INTERVAL);
	}

	@Test
	@DisplayName("처리할 때가 되지 않은 요청이 poll-interval보다 가까우면 그 요청의 처리 시각(도착 + W)까지 기다린다")
	void nearUpcoming() throws Exception {
		voteAt(T0, memberA);
		clock.set(T0.plus(W).minusMillis(50));

		assertThat(voteProcessor.nextRunDelay()).isEqualTo(Duration.ofMillis(50));
	}

	@Test
	@DisplayName("10ms 차이로 도착한 두 요청은 앞의 것을 처리한 직후 10ms 뒤에 돌고, 그때 뒤의 것이 처리된다")
	void tenMillisApart() throws Exception {
		long first = voteAt(T0, memberA);
		long second = voteAt(T0.plusMillis(10), memberB);

		clock.set(T0.plus(W));
		assertThat(voteProcessor.processDue()).isEqualTo(1);
		assertThat(api.requestStatusOf(first)).isEqualTo("CONFIRMED");
		assertThat(api.requestStatusOf(second)).isEqualTo("PENDING");

		Duration delay = voteProcessor.nextRunDelay();
		assertThat(delay).isEqualTo(Duration.ofMillis(10));

		clock.advance(delay);
		assertThat(voteProcessor.processDue()).isEqualTo(1);
		assertThat(api.requestStatusOf(second)).isEqualTo("CONFIRMED");
	}

	@Test
	@DisplayName("처리할 때가 되지 않은 요청이 poll-interval보다 멀면 poll-interval 뒤에 돈다")
	void farUpcoming() throws Exception {
		voteAt(T0, memberA);
		clock.set(T0.plusSeconds(1));

		assertThat(voteProcessor.nextRunDelay()).isEqualTo(POLL_INTERVAL);
	}

	@Test
	@DisplayName("처리할 때가 지났는데 남은 요청만 있으면 0이 아니라 poll-interval 뒤에 돈다")
	void onlyOverdueLeft() throws Exception {
		voteAt(T0, memberA);
		clock.set(T0.plus(W).plusSeconds(1));

		assertThat(voteProcessor.nextRunDelay()).isEqualTo(POLL_INTERVAL);
	}

}
