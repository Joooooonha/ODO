package com.odo.notification.presentation;

import java.util.List;
import java.util.Map;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.support.Concurrently;
import com.odo.support.DatabaseCleaner;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.odo.notification.presentation.NotificationRequests.director;
import static com.odo.notification.presentation.NotificationRequests.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능의 사실 이벤트가 알림으로 남는지. 11_Notification_spec.md의 "기록 (통합)"과 "이벤트와 알림".
 * 일정 4종의 받는 사람은 일정의 승인된 테스트가 확인한다. 여기서는 한 사람과 payload만 본다.
 */
@IntegrationTest
class NotificationRecordIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private NotificationRequests api;

	private long leader;

	private long memberA;

	private long memberB;

	private long teamId;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		jdbc.execute("ALTER TABLE account ALTER COLUMN id RESTART WITH 1000");
		api = new NotificationRequests(mockMvc, accessTokenIssuer, jdbc);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		memberA = TestAccounts.create(jdbc, "김지수", clock.instant());
		memberB = TestAccounts.create(jdbc, "이서연", clock.instant());
		teamId = api.createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, memberA, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, memberB, 26, clock.instant());
	}

	private static long longOf(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

	@Nested
	@DisplayName("입장 요청")
	class JoinRequests {

		private long applicant;

		private long requestId;

		@BeforeEach
		void request() throws Exception {
			applicant = TestAccounts.create(jdbc, "최유나", clock.instant());
			requestId = api.requestJoinId(applicant, api.inviteCode(leader, teamId));
		}

		@Test
		@DisplayName("입장 요청을 보내면 팀장에게 TEAM_JOIN_REQUESTED가 남는다. 대상은 입장 요청이고 한 사람은 요청한 사람이다")
		void requested() {
			List<Map<String, Object>> rows = api.rowsOf(leader);

			assertThat(rows).hasSize(1);
			assertThat(rows.getFirst().get("type")).isEqualTo("TEAM_JOIN_REQUESTED");
			assertThat(longOf(rows.getFirst(), "team_id")).isEqualTo(teamId);
			assertThat(rows.getFirst().get("target_type")).isEqualTo("JOIN_REQUEST");
			assertThat(longOf(rows.getFirst(), "target_id")).isEqualTo(requestId);
			assertThat(longOf(rows.getFirst(), "actor_account_id")).isEqualTo(applicant);
			assertThat(api.rowsOf(applicant)).isEmpty();
		}

		@Test
		@DisplayName("요청한 사람이 취소해도 팀장의 알림은 남는다")
		void canceledKeepsLeaderNotification() throws Exception {
			api.cancelJoin(applicant, requestId).andExpect(status().isNoContent());

			assertThat(api.typesOf(leader)).containsExactly("TEAM_JOIN_REQUESTED");
		}

		@Test
		@DisplayName("승인하면 요청한 사람에게 TEAM_JOIN_APPROVED가 남는다. 대상은 팀이고 한 사람은 팀장이다. 새 팀원은 일정에도 들어간다")
		void approved() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, "화요일 연습");

			api.approve(leader, teamId, requestId).andExpect(status().isOk());

			List<Map<String, Object>> rows = api.rowsOf(applicant);
			assertThat(rows).hasSize(1);
			assertThat(rows.getFirst().get("type")).isEqualTo("TEAM_JOIN_APPROVED");
			assertThat(rows.getFirst().get("target_type")).isEqualTo("TEAM");
			assertThat(longOf(rows.getFirst(), "target_id")).isEqualTo(teamId);
			assertThat(longOf(rows.getFirst(), "actor_account_id")).isEqualTo(leader);
			assertThat(api.typesOf(leader)).containsExactly("TEAM_JOIN_REQUESTED");
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM schedule_participant WHERE schedule_id = ? AND account_id = ?", Integer.class,
					scheduleId, applicant))
				.isEqualTo(1);
		}

		@Test
		@DisplayName("거절하면 요청한 사람에게 TEAM_JOIN_REJECTED가 남는다. 대상은 입장 요청이고 팀 표시가 있다")
		void rejected() throws Exception {
			api.reject(leader, teamId, requestId).andExpect(status().isNoContent());

			List<Map<String, Object>> rows = api.rowsOf(applicant);
			assertThat(rows).hasSize(1);
			assertThat(rows.getFirst().get("type")).isEqualTo("TEAM_JOIN_REJECTED");
			assertThat(longOf(rows.getFirst(), "team_id")).isEqualTo(teamId);
			assertThat(rows.getFirst().get("target_type")).isEqualTo("JOIN_REQUEST");
			assertThat(longOf(rows.getFirst(), "target_id")).isEqualTo(requestId);
			assertThat(longOf(rows.getFirst(), "actor_account_id")).isEqualTo(leader);
		}

	}

	@Nested
	@DisplayName("공연")
	class Performances {

		@Test
		@DisplayName("공연을 만들면 팀장을 뺀 명단 전원에게 명단 추가가, 디렉터로 넣은 사람에게는 디렉터 지정도 남는다. 명단 추가가 먼저다")
		void created() throws Exception {
			long performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연",
					List.of(director(leader), member(memberA), director(memberB)));

			assertThat(api.typesOf(memberA)).containsExactly("PERFORMANCE_MEMBER_ADDED");
			assertThat(api.typesOf(memberB)).containsExactly("PERFORMANCE_MEMBER_ADDED",
					"PERFORMANCE_DIRECTOR_ASSIGNED");
			assertThat(api.rowsOf(leader)).isEmpty();
			Map<String, Object> row = api.rowsOf(memberA).getFirst();
			assertThat(row.get("target_type")).isEqualTo("PERFORMANCE");
			assertThat(longOf(row, "target_id")).isEqualTo(performanceId);
			assertThat(longOf(row, "actor_account_id")).isEqualTo(leader);
		}

		@Test
		@DisplayName("명단에 추가하면 새로 넣은 사람에게만 남는다. 디렉터로 넣은 사람은 두 건이다")
		void added() throws Exception {
			long performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연",
					List.of(director(leader), member(memberA)));
			long memberC = TestAccounts.create(jdbc, "정하늘", clock.instant());
			TestTeams.addMember(jdbc, teamId, memberC, 27, clock.instant());

			api.addMembers(leader, performanceId, List.of(member(memberB), director(memberC)))
				.andExpect(status().isCreated());

			assertThat(api.typesOf(memberA)).containsExactly("PERFORMANCE_MEMBER_ADDED");
			assertThat(api.typesOf(memberB)).containsExactly("PERFORMANCE_MEMBER_ADDED");
			assertThat(api.typesOf(memberC)).containsExactly("PERFORMANCE_MEMBER_ADDED",
					"PERFORMANCE_DIRECTOR_ASSIGNED");
		}

		@Test
		@DisplayName("디렉터로 지정하면 그 사람에게 한 건 남는다. 이미 디렉터인 사람을 다시 지정하면 남지 않는다")
		void designated() throws Exception {
			long performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연",
					List.of(director(leader), member(memberA)));

			api.designateDirector(leader, performanceId, memberA).andExpect(status().isOk());
			api.designateDirector(leader, performanceId, memberA).andExpect(status().isOk());

			assertThat(api.typesOf(memberA)).containsExactly("PERFORMANCE_MEMBER_ADDED",
					"PERFORMANCE_DIRECTOR_ASSIGNED");
		}

		@RepeatedTest(10)
		@DisplayName("같은 사람을 동시에 두 번 디렉터로 지정하면 둘 다 200이고 지정 알림은 한 건이다(PR #16 리뷰)")
		void designatedConcurrently() throws Exception {
			long performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연",
					List.of(director(leader), member(memberA)));

			List<Integer> statuses = Concurrently
				.statuses(Concurrently.run(2, () -> api.designateDirector(leader, performanceId, memberA)));

			assertThat(statuses).containsExactly(200, 200);
			assertThat(api.typesOf(memberA)).containsExactly("PERFORMANCE_MEMBER_ADDED",
					"PERFORMANCE_DIRECTOR_ASSIGNED");
		}

		@Test
		@DisplayName("명단 추가가 실패하면(이미 명단에 있는 사람이 섞임) 알림도 순번도 남지 않는다")
		void rolledBack() throws Exception {
			long performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연",
					List.of(director(leader), member(memberA)));

			api.addMembers(leader, performanceId, List.of(member(memberB), member(memberA)))
				.andExpect(status().isConflict());

			assertThat(api.rowsOf(memberB)).isEmpty();
			assertThat(api.lastSeqOf(memberB)).isZero();
			assertThat(api.seqsOf(memberA)).containsExactly(1L);
		}

	}

	@Nested
	@DisplayName("일정")
	class Schedules {

		@Test
		@DisplayName("일정 알림의 한 사람은 조작한 팀장이다. 등록과 수정 알림의 payload에는 제목을 복사하지 않는다")
		void actorAndNoTitleCopy() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, "화요일 연습");
			api.updateSchedule(leader, scheduleId, "화요일 연습(시간 변경)").andExpect(status().isOk());

			List<Map<String, Object>> rows = api.rowsOf(memberA);
			assertThat(rows).extracting(row -> row.get("type"))
				.containsExactly("SCHEDULE_CREATED", "SCHEDULE_UPDATED");
			assertThat(rows).allSatisfy(row -> {
				assertThat(longOf(row, "actor_account_id")).isEqualTo(leader);
				assertThat(row.get("target_type")).isEqualTo("SCHEDULE");
				assertThat(longOf(row, "target_id")).isEqualTo(scheduleId);
				assertThat(row.get("payload")).isEqualTo("{}");
			});
		}

		@Test
		@DisplayName("삭제 알림의 payload에는 지울 때의 제목이, 상태 변경 알림에는 바뀐 상태가 남는다")
		void payload() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, "화요일 연습");
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());
			api.deleteSchedule(leader, scheduleId).andExpect(status().isNoContent());

			List<Map<String, Object>> rows = api.rowsOf(memberA);
			assertThat(rows).extracting(row -> row.get("type"))
				.containsExactly("SCHEDULE_CREATED", "SCHEDULE_PARTICIPANT_STATUS_CHANGED", "SCHEDULE_DELETED");
			assertThat(jdbc.queryForObject(
					"SELECT payload->>'status' FROM notification WHERE recipient_account_id = ? AND type = 'SCHEDULE_PARTICIPANT_STATUS_CHANGED'",
					String.class, memberA))
				.isEqualTo("REQUIRED");
			assertThat(jdbc.queryForObject(
					"SELECT payload->>'title' FROM notification WHERE recipient_account_id = ? AND type = 'SCHEDULE_DELETED'",
					String.class, memberA))
				.isEqualTo("화요일 연습");
		}

	}

}
