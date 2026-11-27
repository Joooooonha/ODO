package com.odo.notification.application;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import com.odo.account.domain.repository.AccountRepository;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.team.application.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 저장과 받는 사람마다의 순번. 11_Notification_spec.md의 "트랜잭션 안에서 기록하는 순서", "순번",
 * 테스트 방법의 "기록 (통합)"과 "순번 (통합)". 지운 NotificationEventRecorderIntegrationTest가 확인하던 내용을 포함한다.
 */
@IntegrationTest
class NotificationWriterIntegrationTest {

	private static final String TYPE = "SCHEDULE_UPDATED";

	private static final String TARGET_TYPE = "SCHEDULE";

	private static final long TARGET_ID = 77L;

	@Autowired
	NotificationWriter writer;

	@Autowired
	TeamService teamService;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private long leader;

	private long memberA;

	private long memberB;

	private long memberC;

	private long teamId;

	private long otherTeamId;

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 1000");
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		memberC = TestAccounts.create(jdbc, "정하늘", clock.instant());
		teamId = teamService.createTeam(leader, "블랙홀", null, null).id();
		otherTeamId = teamService.createTeam(leader, "화이트홀", null, null).id();
	}

	private NotificationDraft draft(long team, Collection<Long> recipients) {
		return new NotificationDraft(team, TYPE, TARGET_TYPE, TARGET_ID, leader, recipients, Map.of("status", "REQUIRED"));
	}

	private NotificationDraft draft(Collection<Long> recipients) {
		return draft(teamId, recipients);
	}

	private void addInTransaction(NotificationDraft... drafts) {
		transactionTemplate.executeWithoutResult(status -> {
			for (NotificationDraft draft : drafts) {
				writer.add(draft);
			}
		});
	}

	private List<Long> recipientsInDb() {
		return jdbc.queryForList("SELECT recipient_account_id FROM notification ORDER BY recipient_account_id",
				Long.class);
	}

	private List<Long> seqsOf(long recipient) {
		return jdbc.queryForList("SELECT seq FROM notification WHERE recipient_account_id = ? ORDER BY seq",
				Long.class, recipient);
	}

	private long lastSeqOf(long accountId) {
		List<Long> values = jdbc.queryForList("SELECT last_seq FROM notification_inbox WHERE account_id = ?",
				Long.class, accountId);
		return values.isEmpty() ? 0 : values.getFirst();
	}

	@Nested
	@DisplayName("기록")
	class Record {

		@Test
		@DisplayName("받는 사람마다 한 행을 쓰고, 한 사람(조작한 사람)에게는 쓰지 않는다")
		void oneRowPerRecipientExceptActor() {
			addInTransaction(draft(List.of(leader, memberA, memberB)));

			assertThat(recipientsInDb()).containsExactly(memberA, memberB);
		}

		@Test
		@DisplayName("팀, 종류, 대상, 한 사람, payload, 기록한 시각, 순번을 함께 저장한다")
		void storedColumns() {
			addInTransaction(draft(List.of(memberA)));

			Map<String, Object> row = jdbc.queryForMap(
					"SELECT team_id, type, target_type, target_id, actor_account_id, payload->>'status' AS status, created_at, seq "
							+ "FROM notification WHERE recipient_account_id = ?",
					memberA);
			assertThat(((Number) row.get("team_id")).longValue()).isEqualTo(teamId);
			assertThat(row.get("type")).isEqualTo(TYPE);
			assertThat(row.get("target_type")).isEqualTo(TARGET_TYPE);
			assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(TARGET_ID);
			assertThat(((Number) row.get("actor_account_id")).longValue()).isEqualTo(leader);
			assertThat(row.get("status")).isEqualTo("REQUIRED");
			assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(clock.instant());
			assertThat(((Number) row.get("seq")).longValue()).isEqualTo(1L);
		}

		@Test
		@DisplayName("같은 사람이 받는 사람 목록에 두 번 있어도 한 행이다")
		void duplicateRecipient() {
			addInTransaction(draft(List.of(memberA, memberA, memberB)));

			assertThat(recipientsInDb()).containsExactly(memberA, memberB);
			assertThat(seqsOf(memberA)).containsExactly(1L);
		}

		@Test
		@DisplayName("한 사람 말고 받는 사람이 없으면 아무 행도 쓰지 않고 오류도 나지 않는다")
		void onlyActor() {
			addInTransaction(draft(List.of(leader)));
			addInTransaction(draft(List.of()));

			assertThat(recipientsInDb()).isEmpty();
			assertThat(lastSeqOf(leader)).isZero();
		}

		@Test
		@DisplayName("변경이 롤백되면 알림도 순번도 남지 않는다. 같은 트랜잭션이기 때문이다")
		void rolledBackTogether() {
			assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
				writer.add(draft(List.of(memberA, memberB)));
				throw new IllegalStateException("변경 실패");
			})).isInstanceOf(IllegalStateException.class);

			assertThat(recipientsInDb()).isEmpty();
			assertThat(lastSeqOf(memberA)).isZero();
		}

		@Test
		@DisplayName("트랜잭션 밖에서 부르면 기록하지 않고 실패한다. 변경과 따로 커밋되는 일을 막는다")
		void requiresTransaction() {
			assertThatThrownBy(() -> writer.add(draft(List.of(memberA)))).isInstanceOf(IllegalStateException.class);

			assertThat(recipientsInDb()).isEmpty();
		}

	}

	@Nested
	@DisplayName("순번")
	class Seq {

		@Test
		@DisplayName("한 사람의 순번은 1부터 빈 번호 없이 이어진다. 알림이 서로 다른 팀에서 와도 같다")
		void continuousAcrossTeams() {
			addInTransaction(draft(teamId, List.of(memberA)));
			addInTransaction(draft(otherTeamId, List.of(memberA)));
			addInTransaction(draft(teamId, List.of(memberA)));

			assertThat(seqsOf(memberA)).containsExactly(1L, 2L, 3L);
			assertThat(lastSeqOf(memberA)).isEqualTo(3L);
		}

		@Test
		@DisplayName("다른 사람의 순번은 서로 상관없다")
		void independentPerRecipient() {
			addInTransaction(draft(List.of(memberA)));
			addInTransaction(draft(List.of(memberA, memberB)));

			assertThat(seqsOf(memberA)).containsExactly(1L, 2L);
			assertThat(seqsOf(memberB)).containsExactly(1L);
		}

		@Test
		@DisplayName("한 트랜잭션에서 한 사람이 알림 여러 개를 받으면 더한 순서대로 순번이 붙는다")
		void severalDraftsInOneTransaction() {
			NotificationDraft first = new NotificationDraft(teamId, "PERFORMANCE_MEMBER_ADDED", "PERFORMANCE", 5L,
					leader, List.of(memberA, memberB), Map.of());
			NotificationDraft second = new NotificationDraft(teamId, "PERFORMANCE_DIRECTOR_ASSIGNED", "PERFORMANCE",
					5L, leader, List.of(memberB), Map.of());

			addInTransaction(first, second);

			assertThat(jdbc.queryForList("SELECT type FROM notification WHERE recipient_account_id = ? ORDER BY seq",
					String.class, memberB))
				.containsExactly("PERFORMANCE_MEMBER_ADDED", "PERFORMANCE_DIRECTOR_ASSIGNED");
			assertThat(seqsOf(memberB)).containsExactly(1L, 2L);
			assertThat(seqsOf(memberA)).containsExactly(1L);
		}

		@Test
		@DisplayName("알림을 지운 뒤의 새 알림은 이어진 순번을 받는다")
		void continuesAfterDelete() {
			addInTransaction(draft(List.of(memberA)));
			addInTransaction(draft(List.of(memberA)));
			jdbc.update("DELETE FROM notification WHERE recipient_account_id = ?", memberA);

			addInTransaction(draft(List.of(memberA)));

			assertThat(seqsOf(memberA)).containsExactly(3L);
		}

		@Test
		@DisplayName("롤백된 트랜잭션의 순번은 다음 알림이 다시 쓴다. 빈 번호가 없다")
		void rolledBackSeqReused() {
			addInTransaction(draft(List.of(memberA)));
			assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
				writer.add(draft(List.of(memberA)));
				throw new IllegalStateException("변경 실패");
			})).isInstanceOf(IllegalStateException.class);

			addInTransaction(draft(List.of(memberA)));

			assertThat(seqsOf(memberA)).containsExactly(1L, 2L);
		}

		@Test
		@DisplayName("앞 트랜잭션이 순번을 올리고 커밋하지 않은 동안 같은 사람에게 넣는 트랜잭션은 기다리고, 앞의 것이 커밋하면 다음 순번을 받는다")
		void sameOrderAsCommit() throws Exception {
			CountDownLatch seqTaken = new CountDownLatch(1);
			CountDownLatch commit = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					writer.add(new NotificationDraft(teamId, "FIRST", TARGET_TYPE, TARGET_ID, leader,
							List.of(memberA), Map.of()));
					// writer가 먼저 등록한 동기화가 순번을 올린 뒤 이 동기화가 커밋을 붙잡는다.
					TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
						@Override
						public void beforeCommit(boolean readOnly) {
							seqTaken.countDown();
							await(commit);
						}
					});
				}));
				assertThat(seqTaken.await(5, TimeUnit.SECONDS)).as("앞 트랜잭션이 순번을 올렸다").isTrue();

				Future<?> second = executor.submit(() -> addInTransaction(new NotificationDraft(teamId, "SECOND",
						TARGET_TYPE, TARGET_ID, leader, List.of(memberA), Map.of())));
				assertThat(waitUntilSomeoneWaitsForLock()).as("뒤 트랜잭션이 앞의 커밋을 기다린다").isTrue();

				commit.countDown();
				first.get(5, TimeUnit.SECONDS);
				second.get(5, TimeUnit.SECONDS);
			}
			finally {
				commit.countDown();
				executor.shutdownNow();
			}

			assertThat(jdbc.queryForList("SELECT type FROM notification WHERE recipient_account_id = ? ORDER BY seq",
					String.class, memberA))
				.containsExactly("FIRST", "SECOND");
			assertThat(seqsOf(memberA)).containsExactly(1L, 2L);
		}

		@Test
		@DisplayName("앞선 엔티티 변경은 알림함 잠금보다 먼저 DB에 나간다. 알림함 잠금이 트랜잭션의 마지막 잠금이 되게 한다(PR #16 리뷰)")
		void entityChangesFlushedBeforeInboxLock() throws Exception {
			addInTransaction(draft(List.of(memberA)));
			CountDownLatch inboxLocked = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				// 다른 트랜잭션이 김(memberA)의 알림함 행을 잡고 있다.
				Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					jdbc.queryForList("SELECT account_id FROM notification_inbox WHERE account_id = ? FOR UPDATE",
							memberA);
					inboxLocked.countDown();
					await(release);
				}));
				assertThat(inboxLocked.await(5, TimeUnit.SECONDS)).as("알림함 행을 잡았다").isTrue();

				// 계정 이름을 바꾸고(아직 flush 전) 김에게 알림을 더한 뒤 커밋한다. 커밋 직전의 저장이 알림함 잠금에서 기다린다.
				Future<?> writing = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
					accountRepository.findById(memberB).orElseThrow().changeName("이서윤", clock.instant());
					writer.add(draft(List.of(memberA)));
				}));
				assertThat(waitUntilSomeoneWaitsForLock()).as("알림 저장이 알림함 잠금을 기다린다").isTrue();

				// 이름 변경이 먼저 나갔다면 계정 행은 이미 잠겨 있다.
				assertThatThrownBy(() -> jdbc.queryForList("SELECT id FROM account WHERE id = ? FOR UPDATE NOWAIT",
						memberB))
					.as("계정 행이 이미 잠겨 있다")
					.isInstanceOf(DataAccessException.class);

				release.countDown();
				holder.get(5, TimeUnit.SECONDS);
				writing.get(5, TimeUnit.SECONDS);
			}
			finally {
				release.countDown();
				executor.shutdownNow();
			}

			assertThat(seqsOf(memberA)).containsExactly(1L, 2L);
			assertThat(jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, memberB))
				.isEqualTo("이서윤");
		}

		@RepeatedTest(10)
		@DisplayName("받는 사람이 겹치는 트랜잭션을 서로 다른 순서와 나눈 알림으로 동시에 돌려도 교착 없이 모두 성공하고, 순번은 빈 번호 없이 이어진다")
		void noDeadlock() throws Exception {
			List<List<NotificationDraft>> transactions = List.of(
					List.of(draft(List.of(memberA, memberB, memberC))),
					List.of(draft(List.of(memberC, memberB, memberA))),
					List.of(draft(List.of(memberA, memberC)), draft(List.of(memberB))),
					List.of(draft(List.of(memberB)), draft(List.of(memberC, memberA))));
			ExecutorService executor = Executors.newFixedThreadPool(transactions.size());
			CountDownLatch start = new CountDownLatch(1);
			try {
				List<Future<?>> futures = new ArrayList<>();
				for (List<NotificationDraft> drafts : transactions) {
					futures.add(executor.submit(() -> {
						await(start);
						addInTransaction(drafts.toArray(NotificationDraft[]::new));
					}));
				}
				start.countDown();
				for (Future<?> future : futures) {
					future.get(10, TimeUnit.SECONDS);
				}
			}
			finally {
				executor.shutdownNow();
			}

			List<Long> expected = LongStream.rangeClosed(1, transactions.size()).boxed().toList();
			assertThat(seqsOf(memberA)).isEqualTo(expected);
			assertThat(seqsOf(memberB)).isEqualTo(expected);
			assertThat(seqsOf(memberC)).isEqualTo(expected);
		}

	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** 다른 트랜잭션의 잠금을 기다리는 세션이 생길 때까지 5초 동안 본다. */
	private boolean waitUntilSomeoneWaitsForLock() throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < until) {
			Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
			if (waiting != null && waiting > 0) {
				return true;
			}
			Thread.sleep(20);
		}
		return false;
	}

}
