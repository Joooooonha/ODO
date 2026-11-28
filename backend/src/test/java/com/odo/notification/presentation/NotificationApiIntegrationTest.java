package com.odo.notification.presentation;

import java.util.List;
import java.util.Map;

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

import static com.odo.notification.presentation.NotificationRequests.director;
import static com.odo.notification.presentation.NotificationRequests.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 목록, 하나 지우기, 비우기, 새 알림 표시, 열기. 11_Notification_spec.md의 "조회와 지우기 (통합)"과 API 명세.
 * 알림은 각 기능의 API로 만든다. 투표 일정을 만들면 팀장을 뺀 팀원 모두가 알림 하나씩을 받는다.
 */
@IntegrationTest
class NotificationApiIntegrationTest {

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

	/** 팀원 A와 B에게 알림을 n개씩 남긴다. */
	private void notifyMembers(int n) throws Exception {
		for (int i = 1; i <= n; i++) {
			api.createScheduleId(leader, teamId, "연습 " + i);
		}
	}

	/** 팀원 A에게만 알림 하나(공연 명단 추가)를 남긴다. */
	private long notifyOnlyA(String performanceName) throws Exception {
		return api.createPerformanceId(leader, teamId, performanceName, List.of(director(leader), member(memberA)));
	}

	@Nested
	@DisplayName("목록")
	class ListNotifications {

		@Test
		@DisplayName("최신순 20개와 nextCursor가 오고, 마지막 페이지는 nextCursor가 null이다")
		void pages() throws Exception {
			notifyMembers(25);

			api.list(memberA)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(20)))
				.andExpect(jsonPath("$.items[0].seq").value(25))
				.andExpect(jsonPath("$.items[19].seq").value(6))
				.andExpect(jsonPath("$.nextCursor").value(6));
			api.list(memberA, "6")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].seq", contains(5, 4, 3, 2, 1)))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));
		}

		@Test
		@DisplayName("첫 페이지를 받은 뒤 새 알림이 와도 다음 페이지에 중복이나 누락이 없다")
		void stableCursor() throws Exception {
			notifyMembers(21);
			api.list(memberA).andExpect(jsonPath("$.nextCursor").value(2));

			notifyMembers(1);

			api.list(memberA, "2").andExpect(jsonPath("$.items[*].seq", contains(1)));
		}

		@Test
		@DisplayName("항목에 ID, 순번, 종류, 팀, 한 사람, 대상, 내용, 시각이 있다")
		void itemFields() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, "화요일 연습");
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());
			long notificationId = api.notificationIdOf(memberA, 2);

			api.list(memberA)
				.andExpect(jsonPath("$.items[0].id").value(notificationId))
				.andExpect(jsonPath("$.items[0].seq").value(2))
				.andExpect(jsonPath("$.items[0].type").value("SCHEDULE_PARTICIPANT_STATUS_CHANGED"))
				.andExpect(jsonPath("$.items[0].team.id").value(teamId))
				.andExpect(jsonPath("$.items[0].team.name").value("블랙홀"))
				.andExpect(jsonPath("$.items[0].actor.accountId").value(leader))
				.andExpect(jsonPath("$.items[0].actor.name").value("박준하"))
				.andExpect(jsonPath("$.items[0].target.type").value("SCHEDULE"))
				.andExpect(jsonPath("$.items[0].target.id").value(scheduleId))
				.andExpect(jsonPath("$.items[0].target.name").value("화요일 연습"))
				.andExpect(jsonPath("$.items[0].target.deleted").value(false))
				.andExpect(jsonPath("$.items[0].detail.status").value("REQUIRED"))
				.andExpect(jsonPath("$.items[0].createdAt").value("2026-11-20T09:00:00Z"))
				.andExpect(jsonPath("$.items[1].type").value("SCHEDULE_CREATED"))
				.andExpect(jsonPath("$.items[1].detail").value(nullValue()));
		}

		@Test
		@DisplayName("이름은 볼 때 읽는다. 공연 이름이나 한 사람의 이름을 바꾸면 지난 알림에도 새 이름이 보인다")
		void namesReadWhenViewed() throws Exception {
			long performanceId = notifyOnlyA("2026 정기공연");

			api.renamePerformance(leader, performanceId, "2026 겨울 정기공연").andExpect(status().isOk());
			api.rename(leader, "박준하팀장").andExpect(status().isOk());

			api.list(memberA)
				.andExpect(jsonPath("$.items[0].type").value("PERFORMANCE_MEMBER_ADDED"))
				.andExpect(jsonPath("$.items[0].target.type").value("PERFORMANCE"))
				.andExpect(jsonPath("$.items[0].target.id").value(performanceId))
				.andExpect(jsonPath("$.items[0].target.name").value("2026 겨울 정기공연"))
				.andExpect(jsonPath("$.items[0].actor.name").value("박준하팀장"));
		}

		@Test
		@DisplayName("일정 삭제 알림은 지울 때의 제목이고 deleted가 true다. 그 전에 받은 알림은 이름 없이 deleted가 true다")
		void deletedSchedule() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, "화요일 연습");
			api.deleteSchedule(leader, scheduleId).andExpect(status().isNoContent());

			api.list(memberA)
				.andExpect(jsonPath("$.items[0].type").value("SCHEDULE_DELETED"))
				.andExpect(jsonPath("$.items[0].target.name").value("화요일 연습"))
				.andExpect(jsonPath("$.items[0].target.deleted").value(true))
				.andExpect(jsonPath("$.items[1].type").value("SCHEDULE_CREATED"))
				.andExpect(jsonPath("$.items[1].target.id").value(scheduleId))
				.andExpect(jsonPath("$.items[1].target.name").value(nullValue()))
				.andExpect(jsonPath("$.items[1].target.deleted").value(true));
		}

		@Test
		@DisplayName("참여 상태 변경 알림의 내용은 그 뒤에 상태가 다시 바뀌어도 그때의 상태다")
		void statusAtThatTime() throws Exception {
			long scheduleId = api.createScheduleId(leader, teamId, "화요일 연습");
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());
			api.changeStatus(leader, scheduleId, memberA, "OPTIONAL").andExpect(status().isOk());

			api.list(memberA)
				.andExpect(jsonPath("$.items[0].detail.status").value("OPTIONAL"))
				.andExpect(jsonPath("$.items[1].detail.status").value("REQUIRED"));
		}

		@Test
		@DisplayName("입장 요청 알림의 한 사람은 요청한 사람이고, 거절당한 사람은 팀원이 아니어도 거절 알림과 팀 이름을 본다")
		void joinRequests() throws Exception {
			long applicant = TestAccounts.create(jdbc, "최유나", clock.instant());
			long requestId = api.requestJoinId(applicant, api.inviteCode(leader, teamId));
			api.reject(leader, teamId, requestId).andExpect(status().isNoContent());

			api.list(leader)
				.andExpect(jsonPath("$.items[0].type").value("TEAM_JOIN_REQUESTED"))
				.andExpect(jsonPath("$.items[0].actor.accountId").value(applicant))
				.andExpect(jsonPath("$.items[0].actor.name").value("최유나"))
				.andExpect(jsonPath("$.items[0].target.type").value("JOIN_REQUEST"))
				.andExpect(jsonPath("$.items[0].target.id").value(requestId))
				.andExpect(jsonPath("$.items[0].target.name").value(nullValue()))
				.andExpect(jsonPath("$.items[0].target.deleted").value(false));
			api.list(applicant)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].type").value("TEAM_JOIN_REJECTED"))
				.andExpect(jsonPath("$.items[0].team.name").value("블랙홀"))
				.andExpect(jsonPath("$.items[0].actor.name").value("박준하"));
		}

		@Test
		@DisplayName("승인 알림의 대상은 팀이고 이름은 팀 이름이다")
		void approved() throws Exception {
			long applicant = TestAccounts.create(jdbc, "최유나", clock.instant());
			long requestId = api.requestJoinId(applicant, api.inviteCode(leader, teamId));
			api.approve(leader, teamId, requestId).andExpect(status().isOk());

			api.list(applicant)
				.andExpect(jsonPath("$.items[0].type").value("TEAM_JOIN_APPROVED"))
				.andExpect(jsonPath("$.items[0].target.type").value("TEAM"))
				.andExpect(jsonPath("$.items[0].target.id").value(teamId))
				.andExpect(jsonPath("$.items[0].target.name").value("블랙홀"));
		}

		@Test
		@DisplayName("남의 알림은 목록에 없다")
		void onlyMine() throws Exception {
			notifyOnlyA("2026 정기공연");

			api.list(memberB).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
		}

		@Test
		@DisplayName("1보다 작은 커서는 400 INVALID_CURSOR, 숫자가 아닌 커서는 400 INVALID_REQUEST, 로그인하지 않으면 401이다")
		void errors() throws Exception {
			api.list(memberA, "0")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
			api.list(memberA, "abc")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
		}

	}

	@Nested
	@DisplayName("하나 지우기")
	class DeleteOne {

		@Test
		@DisplayName("지우면 204이고 목록에서 사라진다. 다시 지우면 404 NOTIFICATION_NOT_FOUND다")
		void delete() throws Exception {
			notifyMembers(2);
			long first = api.notificationIdOf(memberA, 1);

			api.deleteOne(memberA, first).andExpect(status().isNoContent());

			api.list(memberA).andExpect(jsonPath("$.items[*].seq", contains(2)));
			api.deleteOne(memberA, first)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		}

		@Test
		@DisplayName("남의 알림을 지우면 404 NOTIFICATION_NOT_FOUND이고 그 알림은 남는다")
		void others() throws Exception {
			notifyMembers(1);
			long ofA = api.notificationIdOf(memberA, 1);

			api.deleteOne(memberB, ofA)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));

			assertThat(api.seqsOf(memberA)).containsExactly(1L);
		}

	}

	@Nested
	@DisplayName("비우기")
	class Clear {

		@Test
		@DisplayName("upTo 이하의 내 알림만 지운다. 더 큰 순번의 알림(아직 화면에 뜨지 않은 알림)과 남의 알림은 남는다")
		void upTo() throws Exception {
			notifyMembers(3);

			api.clear(memberA, "2").andExpect(status().isNoContent());

			assertThat(api.seqsOf(memberA)).containsExactly(3L);
			assertThat(api.seqsOf(memberB)).containsExactly(1L, 2L, 3L);
		}

		@Test
		@DisplayName("지울 알림이 없어도 204다")
		void nothingToClear() throws Exception {
			api.clear(memberA, "0").andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("upTo가 없으면 400 INVALID_REQUEST, 음수면 400 INVALID_NOTIFICATION_SEQ다")
		void errors() throws Exception {
			api.clear(memberA, null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			api.clear(memberA, "-1")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SEQ"));
		}

	}

	@Nested
	@DisplayName("새 알림 표시와 열기")
	class UnseenAndOpened {

		@Test
		@DisplayName("알림을 받은 적이 없으면 false이고, 열기를 보내도 204다")
		void neverReceived() throws Exception {
			assertThat(api.hasNew(memberA)).isFalse();

			api.opened(memberA, 0).andExpect(status().isNoContent());

			assertThat(api.hasNew(memberA)).isFalse();
		}

		@Test
		@DisplayName("새 알림이 오면 true, 맨 위 순번으로 열면 false, 그 뒤 새 알림이 오면 다시 true다")
		void basicFlow() throws Exception {
			notifyMembers(2);
			assertThat(api.hasNew(memberA)).isTrue();

			api.opened(memberA, 2).andExpect(status().isNoContent());
			assertThat(api.hasNew(memberA)).isFalse();

			notifyMembers(1);
			assertThat(api.hasNew(memberA)).isTrue();
		}

		@Test
		@DisplayName("맨 위보다 작은 순번으로 열면(화면이 그 뒤의 알림을 아직 못 봄) true가 남는다")
		void openedBelowTop() throws Exception {
			notifyMembers(2);

			api.opened(memberA, 1).andExpect(status().isNoContent());

			assertThat(api.hasNew(memberA)).isTrue();
		}

		@Test
		@DisplayName("지금보다 작은 값으로 다시 열어도 본 순번은 줄지 않는다")
		void neverDecreases() throws Exception {
			notifyMembers(2);
			api.opened(memberA, 2).andExpect(status().isNoContent());

			api.opened(memberA, 1).andExpect(status().isNoContent());

			assertThat(api.hasNew(memberA)).isFalse();
		}

		@Test
		@DisplayName("매긴 순번보다 큰 값으로 열어도, 그 뒤에 새 알림이 오면 true다")
		void openedAboveLast() throws Exception {
			notifyMembers(2);
			api.opened(memberA, 99).andExpect(status().isNoContent());
			assertThat(api.hasNew(memberA)).isFalse();

			notifyMembers(1);

			assertThat(api.hasNew(memberA)).isTrue();
		}

		@Test
		@DisplayName("본 순번보다 큰 알림을 모두 지우면 false다")
		void unseenDeleted() throws Exception {
			notifyMembers(2);
			api.opened(memberA, 1).andExpect(status().isNoContent());

			api.deleteOne(memberA, api.notificationIdOf(memberA, 2)).andExpect(status().isNoContent());

			assertThat(api.hasNew(memberA)).isFalse();
		}

		@Test
		@DisplayName("seq가 없으면 400 INVALID_REQUEST, 음수면 400 INVALID_NOTIFICATION_SEQ다")
		void errors() throws Exception {
			api.opened(memberA, Map.of())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			api.opened(memberA, -1)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SEQ"));
		}

	}

	@Nested
	@DisplayName("CORS")
	class Cors {

		@Test
		@DisplayName("프론트 출처의 실시간 연결 사전 요청에 Last-Event-ID를 허락한다. 화면은 처음 연결부터 이 헤더를 보낸다(PR #17 리뷰)")
		void allowsLastEventId() throws Exception {
			mockMvc
				.perform(options("/api/notifications/stream").header("Origin", "http://localhost:5173")
					.header("Access-Control-Request-Method", "GET")
					.header("Access-Control-Request-Headers", "authorization,last-event-id"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("last-event-id")));
		}

	}

}
