package com.odo.notification.application;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import com.odo.team.application.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 이벤트 기록. 받는 사람마다 한 행을, 변경과 같은 트랜잭션에서 저장하고, 조작한 사람은 뺀다.
 * 11_Notification_spec.md의 "알림 이벤트 기록", 03_Schedule_spec.md의 "알림 이벤트 기록".
 */
@IntegrationTest
class NotificationEventRecorderIntegrationTest {

	private static final String TYPE = "SCHEDULE_UPDATED";

	private static final String TARGET_TYPE = "SCHEDULE";

	private static final long TARGET_ID = 77L;

	@Autowired
	NotificationEventRecorder recorder;

	@Autowired
	TeamService teamService;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private long leader;

	private long memberA;

	private long memberB;

	private long teamId;

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		teamId = teamService.createTeam(leader, "블랙홀", null, null).id();
		TestTeams.addMember(jdbc, teamId, memberA, null, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberB, null, clock.instant());
	}

	private NotificationEventDraft draft() {
		return new NotificationEventDraft(teamId, TYPE, TARGET_TYPE, TARGET_ID, Map.of("title", "화요일 연습"));
	}

	private void recordInTransaction(List<Long> recipients, long actor) {
		transactionTemplate.executeWithoutResult(status -> recorder.record(draft(), recipients, actor));
	}

	private List<Long> recipientsInDb() {
		return jdbc.queryForList("SELECT recipient_account_id FROM notification_event ORDER BY recipient_account_id",
				Long.class);
	}

	@Test
	@DisplayName("받는 사람마다 한 행을 쓰고, 조작한 사람에게는 쓰지 않는다")
	void oneRowPerRecipientExceptActor() {
		recordInTransaction(List.of(leader, memberA, memberB), leader);

		assertThat(recipientsInDb()).containsExactly(memberA, memberB);
	}

	@Test
	@DisplayName("팀, 종류, 대상, 표시할 값의 복사본, 기록한 시각을 함께 저장한다")
	void storedColumns() {
		recordInTransaction(List.of(memberA), leader);

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT team_id, type, target_type, target_id, payload->>'title' AS title, created_at FROM notification_event WHERE recipient_account_id = ?",
				memberA);
		assertThat(((Number) row.get("team_id")).longValue()).isEqualTo(teamId);
		assertThat(row.get("type")).isEqualTo(TYPE);
		assertThat(row.get("target_type")).isEqualTo(TARGET_TYPE);
		assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(TARGET_ID);
		assertThat(row.get("title")).isEqualTo("화요일 연습");
		assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(clock.instant());
	}

	@Test
	@DisplayName("같은 사람이 받는 사람 목록에 두 번 있어도 한 행이다")
	void duplicateRecipient() {
		recordInTransaction(List.of(memberA, memberA, memberB), leader);

		assertThat(recipientsInDb()).containsExactly(memberA, memberB);
	}

	@Test
	@DisplayName("조작한 사람 말고 받는 사람이 없으면 아무 행도 쓰지 않고 오류도 나지 않는다")
	void onlyActor() {
		recordInTransaction(List.of(leader), leader);
		recordInTransaction(List.of(), leader);

		assertThat(recipientsInDb()).isEmpty();
	}

	@Test
	@DisplayName("변경이 롤백되면 알림 기록도 남지 않는다. 같은 트랜잭션이기 때문이다")
	void rolledBackTogether() {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			recorder.record(draft(), List.of(memberA, memberB), leader);
			throw new IllegalStateException("변경 실패");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(recipientsInDb()).isEmpty();
	}

	@Test
	@DisplayName("트랜잭션 밖에서 부르면 기록하지 않고 실패한다. 변경과 따로 커밋되는 일을 막는다")
	void requiresTransaction() {
		assertThatThrownBy(() -> recorder.record(draft(), List.of(memberA), leader))
			.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(recipientsInDb()).isEmpty();
	}

}
