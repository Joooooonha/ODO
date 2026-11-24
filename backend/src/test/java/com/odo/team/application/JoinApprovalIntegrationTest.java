package com.odo.team.application;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.team.application.dto.TeamMemberResult;
import com.odo.team.domain.event.TeamMemberJoinedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 일정 기능을 위한 승인 변경. 승인은 맨 처음 팀 행을 FOR UPDATE로 잠그고, 팀원을 넣은 뒤 같은 트랜잭션에서
 * TeamMemberJoinedEvent를 발행한다. 02_TeamSpace_spec.md의 입장 요청 상태, 03_Schedule_spec.md의 "참여자 행과 새 팀원".
 * 승인과 거절의 다른 동작은 JoinRequestIntegrationTest가 확인한다.
 */
@IntegrationTest
@RecordApplicationEvents
class JoinApprovalIntegrationTest {

	@Autowired
	JoinRequestService joinRequestService;

	@Autowired
	TeamService teamService;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	ApplicationEvents events;

	@Autowired
	FailingJoinListener failingJoinListener;

	private long leader;

	private long applicant;

	private long teamId;

	private long requestId;

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		applicant = TestAccounts.create(jdbc, "김지수", clock.instant());
		teamId = teamService.createTeam(leader, "블랙홀", null, null).id();
		String inviteCode = teamService.findInviteCode(leader, teamId);
		requestId = joinRequestService.requestJoin(applicant, inviteCode, 25).requestId();
	}

	@AfterEach
	void tearDown() {
		failingJoinListener.failFor = null;
	}

	private int countMembers() {
		return jdbc.queryForObject("SELECT count(*) FROM team_member WHERE team_id = ?", Integer.class, teamId);
	}

	private String requestStatus() {
		return jdbc.queryForObject("SELECT status FROM join_request WHERE id = ?", String.class, requestId);
	}

	@Test
	@DisplayName("승인하면 그 팀과 새 팀원을 담은 TeamMemberJoinedEvent가 한 번 발행된다")
	void approvePublishesEvent() {
		joinRequestService.approve(leader, teamId, requestId);

		assertThat(events.stream(TeamMemberJoinedEvent.class))
			.containsExactly(new TeamMemberJoinedEvent(teamId, applicant));
	}

	@Test
	@DisplayName("거절하면 이벤트가 발행되지 않는다")
	void rejectPublishesNothing() {
		joinRequestService.reject(leader, teamId, requestId);

		assertThat(events.stream(TeamMemberJoinedEvent.class)).isEmpty();
	}

	@Test
	@DisplayName("이벤트를 받은 쪽이 실패하면 승인도 롤백된다. 팀원 추가와 이벤트 처리가 같은 트랜잭션이기 때문이다")
	void listenerFailureRollsBackApproval() {
		failingJoinListener.failFor = applicant;

		assertThatThrownBy(() -> joinRequestService.approve(leader, teamId, requestId))
			.isInstanceOf(IllegalStateException.class);

		assertThat(countMembers()).isEqualTo(1);
		assertThat(requestStatus()).isEqualTo("PENDING");
	}

	/**
	 * 다른 트랜잭션이 팀 행을 FOR NO KEY UPDATE로 잡고 있으면 승인이 기다리는지 본다.
	 * 팀원 INSERT의 FK 확인(FOR KEY SHARE)은 이 잠금과 충돌하지 않으므로, 승인이 기다리는 것은 승인이 팀 행을 FOR UPDATE로 잠글 때뿐이다.
	 */
	@Test
	@DisplayName("다른 트랜잭션이 팀 행을 잠그고 있으면 승인은 잠금이 풀릴 때까지 기다린다")
	void approvalWaitsForTeamRowLock() throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				jdbc.queryForObject("SELECT id FROM team WHERE id = ? FOR NO KEY UPDATE", Long.class, teamId);
				locked.countDown();
				awaitQuietly(release);
			}));
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

			Future<TeamMemberResult> approving = executor
				.submit(() -> joinRequestService.approve(leader, teamId, requestId));

			assertThatThrownBy(() -> approving.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			assertThat(countMembers()).isEqualTo(1);

			release.countDown();
			holder.get(5, TimeUnit.SECONDS);
			assertThat(approving.get(5, TimeUnit.SECONDS).accountId()).isEqualTo(applicant);
			assertThat(countMembers()).isEqualTo(2);
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingListenerConfiguration {

		@Bean
		FailingJoinListener failingJoinListener() {
			return new FailingJoinListener();
		}

	}

	/** 정한 계정의 이벤트를 받으면 실패한다. 승인과 이벤트 처리가 같은 트랜잭션인지 확인하는 데만 쓴다. */
	static class FailingJoinListener {

		volatile Long failFor;

		@EventListener
		void onJoined(TeamMemberJoinedEvent event) {
			if (Objects.equals(failFor, event.accountId())) {
				throw new IllegalStateException("리스너 실패");
			}
		}

	}

}
