package com.odo.schedule.presentation;

import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.odo.schedule.presentation.ScheduleRequests.DEADLINE;
import static com.odo.schedule.presentation.ScheduleRequests.END;
import static com.odo.schedule.presentation.ScheduleRequests.START;
import static com.odo.schedule.presentation.ScheduleRequests.idOf;
import static com.odo.schedule.presentation.ScheduleRequests.instant;
import static com.odo.schedule.presentation.ScheduleRequests.instantAt;
import static com.odo.schedule.presentation.ScheduleRequests.roomBody;
import static com.odo.schedule.presentation.ScheduleRequests.scheduleBody;
import static com.odo.schedule.presentation.ScheduleRequests.updateBody;
import static com.odo.schedule.presentation.ScheduleRequests.votingBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일정 만들기, 상세, 목록, 수정, 삭제와 그 알림 이벤트. 03_Schedule_spec.md의 "일정 (통합)"과 API 명세의 일정 부분.
 * 테스트 시계는 2026-11-20 18:00(한국 시간)에서 시작한다. 기본 일정은 2026-11-25 19:00~21:00이다.
 */
@IntegrationTest
class ScheduleApiIntegrationTest {

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

	/** 어느 팀에도 없다. 다른 팀을 만들 때 그 팀의 팀장이 된다. */
	private long outsider;

	private long teamId;

	private long roomId;

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
		Map<String, Object> room = roomBody("합정 A룸");
		room.put("address", "서울 마포구 양화로 1");
		room.put("password", "1234*");
		roomId = api.createRoomId(leader, teamId, room);
	}

	private long create(Map<String, Object> body) throws Exception {
		return api.createScheduleId(leader, teamId, body);
	}

	private String notifiedTitle(String type) {
		return jdbc.queryForObject("SELECT payload->>'title' FROM notification_event WHERE type = ? LIMIT 1",
				String.class, type);
	}

	@Nested
	@DisplayName("만들기")
	class Create {

		@Test
		@DisplayName("팀장이 투표 없는 일정을 만들면 201과 Location, 일정 상세를 받는다. 종료 시간은 시작에 연습 시간을 더한 값이다")
		void nonVoting() throws Exception {
			Map<String, Object> body = scheduleBody("  자유 연습  ");
			body.put("practiceRoomId", roomId);
			body.put("memo", "물 챙기기\n늦으면 연락");

			ResultActions result = api.createSchedule(leader, teamId, body)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern("/api/schedules/\\d+")))
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.type").value("PRACTICE"))
				.andExpect(jsonPath("$.title").value("자유 연습"))
				.andExpect(jsonPath("$.durationMinutes").value(120))
				.andExpect(jsonPath("$.status").value("UPCOMING"))
				.andExpect(jsonPath("$.practiceRoom.id").value(roomId))
				.andExpect(jsonPath("$.practiceRoom.name").value("합정 A룸"))
				.andExpect(jsonPath("$.practiceRoom.password").value("1234*"))
				.andExpect(jsonPath("$.practiceRoom.deleted").value(false))
				.andExpect(jsonPath("$.memo").value("물 챙기기\n늦으면 연락"))
				.andExpect(jsonPath("$.votingEnabled").value(false))
				.andExpect(jsonPath("$.voteDeadline").value(nullValue()))
				.andExpect(jsonPath("$.capacity").value(nullValue()))
				.andExpect(jsonPath("$.myStatus").value("EXCLUDED"))
				.andExpect(jsonPath("$.myVote").value(nullValue()))
				.andExpect(jsonPath("$.confirmedCount").value(0))
				.andExpect(jsonPath("$.requiredCount").value(0));

			assertThat(instantAt(result, "$.startAt")).isEqualTo(instant(START));
			assertThat(instantAt(result, "$.endAt")).isEqualTo(instant(END));
		}

		@Test
		@DisplayName("투표 없는 일정은 필수로 보낸 사람이 필수, 나머지 팀원(팀장 포함)은 제외로 들어간다")
		void nonVotingParticipants() throws Exception {
			Map<String, Object> body = scheduleBody("공연팀 연습");
			body.put("requiredAccountIds", List.of(memberA));

			long scheduleId = create(body);

			assertThat(api.statusOf(scheduleId, memberA)).isEqualTo("REQUIRED");
			assertThat(api.statusOf(scheduleId, leader)).isEqualTo("EXCLUDED");
			assertThat(api.statusOf(scheduleId, memberB)).isEqualTo("EXCLUDED");
		}

		@Test
		@DisplayName("투표 일정은 필수로 보낸 사람이 필수, 나머지는 미투표 선택으로 들어가고 정원과 마감 기한이 저장된다")
		void votingParticipants() throws Exception {
			Map<String, Object> body = votingBody("자유 연습");
			body.put("capacity", 8);
			body.put("requiredAccountIds", List.of(memberA));

			ResultActions result = api.createSchedule(leader, teamId, body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.votingEnabled").value(true))
				.andExpect(jsonPath("$.votingClosed").value(false))
				.andExpect(jsonPath("$.capacity").value(8))
				.andExpect(jsonPath("$.requiredCount").value(1))
				.andExpect(jsonPath("$.myStatus").value("OPTIONAL"));
			long scheduleId = idOf(result);

			assertThat(instantAt(result, "$.voteDeadline")).isEqualTo(instant(DEADLINE));
			assertThat(api.statusOf(scheduleId, memberA)).isEqualTo("REQUIRED");
			assertThat(api.statusOf(scheduleId, leader)).isEqualTo("OPTIONAL");
			assertThat(api.statusOf(scheduleId, memberB)).isEqualTo("OPTIONAL");
			assertThat(api.voteOf(scheduleId, memberB)).isNull();
		}

		@Test
		@DisplayName("이틀에 걸친 일정도 만들 수 있다")
		void crossesMidnight() throws Exception {
			Map<String, Object> body = scheduleBody("밤샘 연습");
			body.put("startAt", "2026-11-25T23:00:00+09:00");
			body.put("durationMinutes", 180);

			ResultActions result = api.createSchedule(leader, teamId, body).andExpect(status().isCreated());

			assertThat(instantAt(result, "$.endAt")).isEqualTo(instant("2026-11-26T02:00:00+09:00"));
		}

		@Test
		@DisplayName("장소 없이 만들 수 있다")
		void withoutPlace() throws Exception {
			api.createSchedule(leader, teamId, scheduleBody("장소 미정 연습"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.practiceRoom").value(nullValue()));
		}

		@Test
		@DisplayName("마감 기한이 없으면 만든 시점부터 24시간 뒤를 10분 단위로 올린 시각이다")
		void defaultDeadline() throws Exception {
			clock.set(Instant.parse("2026-11-20T06:32:00Z"));
			Map<String, Object> body = votingBody("자유 연습");
			body.remove("voteDeadline");

			ResultActions result = api.createSchedule(leader, teamId, body).andExpect(status().isCreated());

			assertThat(instantAt(result, "$.voteDeadline")).isEqualTo(Instant.parse("2026-11-21T06:40:00Z"));
		}

		@Test
		@DisplayName("연습 시작이 기본값보다 이르면 마감 기한의 기본값은 연습 시작 시간이다")
		void defaultDeadlineCappedByStart() throws Exception {
			Map<String, Object> body = votingBody("내일 연습");
			body.remove("voteDeadline");
			body.put("startAt", "2026-11-21T12:00:00+09:00");

			ResultActions result = api.createSchedule(leader, teamId, body).andExpect(status().isCreated());

			assertThat(instantAt(result, "$.voteDeadline")).isEqualTo(instant("2026-11-21T12:00:00+09:00"));
		}

		@Test
		@DisplayName("마감 기한을 연습 시작 시간과 같게 정할 수 있다")
		void deadlineEqualsStart() throws Exception {
			Map<String, Object> body = votingBody("자유 연습");
			body.put("voteDeadline", START);

			api.createSchedule(leader, teamId, body).andExpect(status().isCreated());
		}

		@Test
		@DisplayName("팀장이 아닌 팀원은 403 NOT_TEAM_LEADER, 팀원이 아니면 404 TEAM_NOT_FOUND이고 만들어지지 않는다")
		void permission() throws Exception {
			api.createSchedule(memberA, teamId, scheduleBody("자유 연습"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.createSchedule(outsider, teamId, scheduleBody("자유 연습"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));

			assertThat(api.countSchedules(teamId)).isZero();
		}

		@Test
		@DisplayName("입력이 규칙에 맞지 않으면 그 항목의 400이고, 일정과 참여자 행이 만들어지지 않는다")
		void invalidInput() throws Exception {
			long otherTeam = api.createTeam(outsider, "다른 팀");
			long otherTeamRoom = api.createRoomId(outsider, otherTeam, "다른 팀 연습실");
			long deletedRoom = api.createRoomId(leader, teamId, "없어진 연습실");
			api.deleteRoom(leader, deletedRoom);

			assertInvalid(with(scheduleBody(" "), "title", " "), "INVALID_SCHEDULE_TITLE");
			assertInvalid(without(scheduleBody("x"), "title"), "INVALID_SCHEDULE_TITLE");
			assertInvalid(scheduleBody("가".repeat(51)), "INVALID_SCHEDULE_TITLE");
			assertInvalid(scheduleBody("연습 😀"), "INVALID_SCHEDULE_TITLE");
			assertInvalid(with(scheduleBody("연습"), "memo", "가".repeat(301)), "INVALID_SCHEDULE_MEMO");
			assertInvalid(with(scheduleBody("연습"), "startAt", "2026-11-25T19:05:00+09:00"), "INVALID_START_AT");
			assertInvalid(with(scheduleBody("연습"), "startAt", "2026-11-20T17:00:00+09:00"), "INVALID_START_AT");
			assertInvalid(with(scheduleBody("연습"), "durationMinutes", 45), "INVALID_DURATION");
			assertInvalid(with(scheduleBody("연습"), "durationMinutes", 20), "INVALID_DURATION");
			assertInvalid(with(scheduleBody("연습"), "durationMinutes", 1470), "INVALID_DURATION");
			assertInvalid(with(scheduleBody("연습"), "practiceRoomId", otherTeamRoom), "INVALID_PRACTICE_ROOM");
			assertInvalid(with(scheduleBody("연습"), "practiceRoomId", deletedRoom), "INVALID_PRACTICE_ROOM");
			assertInvalid(with(scheduleBody("연습"), "practiceRoomId", 999_999L), "INVALID_PRACTICE_ROOM");
			assertInvalid(with(scheduleBody("연습"), "voteDeadline", DEADLINE), "NOT_VOTING_SCHEDULE");
			assertInvalid(with(scheduleBody("연습"), "capacity", 5), "NOT_VOTING_SCHEDULE");
			assertInvalid(with(votingBody("연습"), "voteDeadline", "2026-11-24T19:05:00+09:00"), "INVALID_VOTE_DEADLINE");
			assertInvalid(with(votingBody("연습"), "voteDeadline", "2026-11-20T17:00:00+09:00"), "INVALID_VOTE_DEADLINE");
			assertInvalid(with(votingBody("연습"), "voteDeadline", "2026-11-25T19:10:00+09:00"), "INVALID_VOTE_DEADLINE");
			assertInvalid(with(votingBody("연습"), "capacity", 0), "INVALID_CAPACITY");
			assertInvalid(with(scheduleBody("연습"), "requiredAccountIds", List.of(memberA, memberA)), "DUPLICATE_MEMBER");
			assertInvalid(with(scheduleBody("연습"), "requiredAccountIds", List.of(outsider)), "NOT_TEAM_MEMBER");

			assertThat(api.countSchedules(teamId)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_participant", Integer.class)).isZero();
		}

		@ParameterizedTest
		@ValueSource(strings = { "startAt", "durationMinutes", "votingEnabled" })
		@DisplayName("시작 시간, 연습 시간, 투표 여부가 없으면 400 INVALID_REQUEST다")
		void requiredValueMissing(String field) throws Exception {
			api.createSchedule(leader, teamId, without(scheduleBody("연습"), field))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("필수와 선택 참여자에게 SCHEDULE_CREATED 알림 이벤트가 남고, 만든 팀장 본인과 제외된 사람에게는 남지 않는다")
		void notification() throws Exception {
			Map<String, Object> voting = votingBody("자유 연습");
			voting.put("requiredAccountIds", List.of(memberA));
			create(voting);

			assertThat(api.notified("SCHEDULE_CREATED")).containsExactly(memberA, memberB);
			assertThat(notifiedTitle("SCHEDULE_CREATED")).isEqualTo("자유 연습");

			jdbc.update("DELETE FROM notification_event");
			Map<String, Object> nonVoting = scheduleBody("공연팀 연습");
			nonVoting.put("requiredAccountIds", List.of(memberA));
			create(nonVoting);

			assertThat(api.notified("SCHEDULE_CREATED")).containsExactly(memberA);
		}

		private void assertInvalid(Map<String, Object> body, String code) throws Exception {
			api.createSchedule(leader, teamId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(code));
		}

	}

	@Nested
	@DisplayName("상세")
	class Detail {

		@Test
		@DisplayName("팀원은 제외된 사람도 볼 수 있고, 팀원이 아니거나 없는 일정은 404 SCHEDULE_NOT_FOUND다")
		void visibility() throws Exception {
			long scheduleId = create(with(scheduleBody("공연팀 연습"), "requiredAccountIds", List.of(memberA)));

			api.schedule(memberB, scheduleId).andExpect(status().isOk()).andExpect(jsonPath("$.myStatus").value("EXCLUDED"));
			api.schedule(outsider, scheduleId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
			api.schedule(memberA, 999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
		}

		@Test
		@DisplayName("참여 인원은 참여로 투표한 선택 참여자만 세고, 내 상태와 내 투표가 함께 온다")
		void countsAndMine() throws Exception {
			Map<String, Object> body = votingBody("자유 연습");
			body.put("capacity", 8);
			body.put("requiredAccountIds", List.of(memberA));
			long scheduleId = create(body);
			api.setVote(scheduleId, leader, "YES", clock.instant());
			api.setVote(scheduleId, memberB, "NO", clock.instant());

			api.schedule(leader, scheduleId)
				.andExpect(jsonPath("$.confirmedCount").value(1))
				.andExpect(jsonPath("$.requiredCount").value(1))
				.andExpect(jsonPath("$.myStatus").value("OPTIONAL"))
				.andExpect(jsonPath("$.myVote").value("YES"));
			api.schedule(memberA, scheduleId)
				.andExpect(jsonPath("$.myStatus").value("REQUIRED"))
				.andExpect(jsonPath("$.myVote").value(nullValue()));
			api.schedule(memberB, scheduleId).andExpect(jsonPath("$.myVote").value("NO"));
		}

		@Test
		@DisplayName("마감 기한이 되면 votingClosed가 true, 종료 시간이 지나면 status가 DONE이다")
		void computedAtReadTime() throws Exception {
			long scheduleId = create(votingBody("자유 연습"));

			clock.set(instant(DEADLINE).minusSeconds(1));
			api.schedule(memberA, scheduleId)
				.andExpect(jsonPath("$.votingClosed").value(false))
				.andExpect(jsonPath("$.status").value("UPCOMING"));

			clock.set(instant(DEADLINE));
			api.schedule(memberA, scheduleId).andExpect(jsonPath("$.votingClosed").value(true));

			clock.set(instant(START).plus(Duration.ofMinutes(30)));
			api.schedule(memberA, scheduleId).andExpect(jsonPath("$.status").value("UPCOMING"));

			clock.set(instant(END));
			api.schedule(memberA, scheduleId).andExpect(jsonPath("$.status").value("DONE"));
		}

		@Test
		@DisplayName("연습실을 고치면 일정에 바뀐 내용이 보이고, 연습실을 삭제해도 정보는 그대로 보이며 deleted가 true다")
		void roomChanges() throws Exception {
			long scheduleId = create(with(scheduleBody("자유 연습"), "practiceRoomId", roomId));
			Map<String, Object> changed = roomBody("합정 A룸(리모델링)");
			changed.put("password", "5678#");
			api.updateRoom(leader, roomId, changed).andExpect(status().isOk());

			api.schedule(memberA, scheduleId)
				.andExpect(jsonPath("$.practiceRoom.name").value("합정 A룸(리모델링)"))
				.andExpect(jsonPath("$.practiceRoom.password").value("5678#"));

			api.deleteRoom(leader, roomId).andExpect(status().isNoContent());

			api.schedule(memberA, scheduleId)
				.andExpect(jsonPath("$.practiceRoom.name").value("합정 A룸(리모델링)"))
				.andExpect(jsonPath("$.practiceRoom.password").value("5678#"))
				.andExpect(jsonPath("$.practiceRoom.deleted").value(true));
		}

	}

	@Nested
	@DisplayName("목록")
	class ListSchedules {

		@Test
		@DisplayName("예정은 시작이 가까운 순이고, 시작이 같으면 먼저 만든 일정이 앞이다. 항목에 목록용 값이 온다")
		void upcomingOrder() throws Exception {
			long later = create(with(scheduleBody("목요일 연습"), "startAt", "2026-11-26T19:00:00+09:00"));
			Map<String, Object> voting = with(votingBody("수요일 연습"), "practiceRoomId", roomId);
			voting.put("capacity", 8);
			long first = create(voting);
			long sameStart = create(scheduleBody("수요일 보충"));
			api.setVote(first, leader, "YES", clock.instant());

			api.listSchedules(leader, teamId, "?status=UPCOMING")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(3)))
				.andExpect(jsonPath("$.items[0].id").value(first))
				.andExpect(jsonPath("$.items[1].id").value(sameStart))
				.andExpect(jsonPath("$.items[2].id").value(later))
				.andExpect(jsonPath("$.items[0].title").value("수요일 연습"))
				.andExpect(jsonPath("$.items[0].practiceRoomName").value("합정 A룸"))
				.andExpect(jsonPath("$.items[0].votingEnabled").value(true))
				.andExpect(jsonPath("$.items[0].capacity").value(8))
				.andExpect(jsonPath("$.items[0].confirmedCount").value(1))
				.andExpect(jsonPath("$.items[0].myStatus").value("OPTIONAL"))
				.andExpect(jsonPath("$.items[0].myVote").value("YES"))
				.andExpect(jsonPath("$.items[1].practiceRoomName").value(nullValue()))
				.andExpect(jsonPath("$.items[1].myVote").value(nullValue()))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalCount").value(3));
		}

		@Test
		@DisplayName("status를 빼면 예정이다. 진행 중인 일정은 예정에 있고, 종료 시간이 지나면 완료로 넘어간다")
		void upcomingToDone() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));

			clock.set(instant(START).plus(Duration.ofMinutes(30)));
			api.listSchedules(memberA, teamId, "")
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(scheduleId));

			clock.set(instant(END));
			api.listSchedules(memberA, teamId, "?status=UPCOMING").andExpect(jsonPath("$.items", hasSize(0)));
			api.listSchedules(memberA, teamId, "?status=DONE")
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(scheduleId));
		}

		@Test
		@DisplayName("완료는 최근에 끝난 순이다")
		void doneOrder() throws Exception {
			long earlier = create(scheduleBody("월요일 연습"));
			long longer = create(with(scheduleBody("월요일 긴 연습"), "durationMinutes", 240));

			clock.set(instant("2026-11-27T00:00:00+09:00"));

			api.listSchedules(memberA, teamId, "?status=DONE")
				.andExpect(jsonPath("$.items[0].id").value(longer))
				.andExpect(jsonPath("$.items[1].id").value(earlier));
		}

		@Test
		@DisplayName("페이지 번호와 크기로 나눠 보고, 전체 개수가 함께 온다")
		void paging() throws Exception {
			for (int day = 25; day <= 27; day++) {
				create(with(scheduleBody(day + "일 연습"), "startAt", "2026-11-" + day + "T19:00:00+09:00"));
			}

			api.listSchedules(memberA, teamId, "?page=0&size=2")
				.andExpect(jsonPath("$.items", hasSize(2)))
				.andExpect(jsonPath("$.items[0].title").value("25일 연습"))
				.andExpect(jsonPath("$.totalCount").value(3));
			api.listSchedules(memberA, teamId, "?page=1&size=2")
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].title").value("27일 연습"))
				.andExpect(jsonPath("$.page").value(1));
			api.listSchedules(memberA, teamId, "?size=50").andExpect(status().isOk());
		}

		@ParameterizedTest
		@ValueSource(strings = { "?size=0", "?size=51", "?page=-1", "?status=LATER" })
		@DisplayName("페이지, 크기, 상태가 범위를 벗어나면 400 INVALID_REQUEST다")
		void invalidQuery(String query) throws Exception {
			api.listSchedules(memberA, teamId, query)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("그 팀의 일정만 보이고, 팀원이 아니면 404 TEAM_NOT_FOUND다")
		void teamScope() throws Exception {
			long otherTeam = api.createTeam(outsider, "다른 팀");
			api.createScheduleId(outsider, otherTeam, scheduleBody("다른 팀 연습"));
			create(scheduleBody("우리 연습"));

			api.listSchedules(memberA, teamId, "")
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].title").value("우리 연습"));
			api.listSchedules(memberA, otherTeam, "")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("수정")
	class Update {

		@Test
		@DisplayName("팀장은 보낸 값으로 모두 바꾸고, 종료 시간도 다시 계산된다")
		void update() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));
			Map<String, Object> body = updateBody(scheduleBody("자유 연습(장소 확정)"));
			body.put("durationMinutes", 180);
			body.put("practiceRoomId", roomId);
			body.put("memo", "물 챙기기");

			ResultActions result = api.updateSchedule(leader, scheduleId, body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("자유 연습(장소 확정)"))
				.andExpect(jsonPath("$.durationMinutes").value(180))
				.andExpect(jsonPath("$.practiceRoom.id").value(roomId))
				.andExpect(jsonPath("$.memo").value("물 챙기기"));

			assertThat(instantAt(result, "$.endAt")).isEqualTo(instant("2026-11-25T22:00:00+09:00"));
		}

		@Test
		@DisplayName("팀장이 아니면 403 NOT_TEAM_LEADER, 팀원이 아니면 404 SCHEDULE_NOT_FOUND다")
		void permission() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));

			api.updateSchedule(memberA, scheduleId, updateBody(scheduleBody("바꾼 제목")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.updateSchedule(outsider, scheduleId, updateBody(scheduleBody("바꾼 제목")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
		}

		@Test
		@DisplayName("종료 시간이 지난 일정은 409 SCHEDULE_ENDED이고, 삭제는 할 수 있다")
		void ended() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));
			clock.set(instant(END));

			api.updateSchedule(leader, scheduleId, updateBody(scheduleBody("바꾼 제목")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SCHEDULE_ENDED"));
			api.deleteSchedule(leader, scheduleId).andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("시작한 일정도 시작 시간을 그대로 두면 종료 전까지 메모를 고칠 수 있고, 시작 시간을 현재 이전으로 바꿀 수는 없다")
		void startedSchedule() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));
			clock.set(instant(START).plus(Duration.ofMinutes(30)));

			api.updateSchedule(leader, scheduleId, with(updateBody(scheduleBody("자유 연습")), "memo", "10분 늦게 시작"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memo").value("10분 늦게 시작"));
			api.updateSchedule(leader, scheduleId,
					with(updateBody(scheduleBody("자유 연습")), "startAt", "2026-11-25T19:10:00+09:00"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_START_AT"));
		}

		@Test
		@DisplayName("고친 뒤의 종료 시간이 현재 이전이면 400 INVALID_DURATION이다")
		void endInPast() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));
			clock.set(instant(START).plus(Duration.ofMinutes(40)));

			api.updateSchedule(leader, scheduleId, with(updateBody(scheduleBody("자유 연습")), "durationMinutes", 30))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DURATION"));
		}

		@Test
		@DisplayName("삭제한 연습실은 장소를 그대로 두면 통과하고, 다른 일정의 장소로 새로 고를 수는 없다")
		void deletedRoom() throws Exception {
			long scheduleId = create(with(scheduleBody("자유 연습"), "practiceRoomId", roomId));
			long other = create(scheduleBody("다른 연습"));
			api.deleteRoom(leader, roomId).andExpect(status().isNoContent());

			api.updateSchedule(leader, scheduleId,
					with(with(updateBody(scheduleBody("자유 연습")), "practiceRoomId", roomId), "memo", "메모만 고침"))
				.andExpect(status().isOk());
			api.updateSchedule(leader, other, with(updateBody(scheduleBody("다른 연습")), "practiceRoomId", roomId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_ROOM"));
		}

		@Test
		@DisplayName("투표 여부는 바꿀 수 없다. 보내도 무시되고, 투표 없는 일정에 마감 기한을 보내면 400 NOT_VOTING_SCHEDULE이다")
		void votingFixed() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));

			api.updateSchedule(leader, scheduleId, with(updateBody(scheduleBody("자유 연습")), "votingEnabled", true))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.votingEnabled").value(false));
			api.updateSchedule(leader, scheduleId, with(updateBody(scheduleBody("자유 연습")), "voteDeadline", DEADLINE))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("NOT_VOTING_SCHEDULE"));
		}

		@Test
		@DisplayName("마감 뒤에 마감 기한이나 정원을 바꾸면 409 VOTING_CLOSED이고, 저장된 값과 같으면 다른 항목을 고칠 수 있다")
		void afterDeadline() throws Exception {
			Map<String, Object> created = with(votingBody("자유 연습"), "capacity", 8);
			long scheduleId = create(created);
			clock.set(instant(DEADLINE).plus(Duration.ofMinutes(10)));

			api.updateSchedule(leader, scheduleId,
					with(updateBody(created), "voteDeadline", "2026-11-25T12:00:00+09:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VOTING_CLOSED"));
			api.updateSchedule(leader, scheduleId, with(updateBody(created), "capacity", 10))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VOTING_CLOSED"));
			api.updateSchedule(leader, scheduleId, with(updateBody(created), "memo", "장소 공지 예정"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memo").value("장소 공지 예정"));
		}

		@Test
		@DisplayName("정원을 지금 참여 인원보다 작게 하면 409 CAPACITY_BELOW_CONFIRMED이고, 같게 하거나 비우거나 새로 정할 수 있다")
		void capacityChanges() throws Exception {
			Map<String, Object> created = with(votingBody("자유 연습"), "capacity", 8);
			long scheduleId = create(created);
			api.setVote(scheduleId, leader, "YES", clock.instant());
			api.setVote(scheduleId, memberB, "YES", clock.instant());

			api.updateSchedule(leader, scheduleId, with(updateBody(created), "capacity", 1))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CAPACITY_BELOW_CONFIRMED"));
			api.updateSchedule(leader, scheduleId, with(updateBody(created), "capacity", 2))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.capacity").value(2));
			api.updateSchedule(leader, scheduleId, without(updateBody(created), "capacity"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.capacity").value(nullValue()));
			api.updateSchedule(leader, scheduleId, with(updateBody(created), "capacity", 5))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.capacity").value(5));
		}

		@Test
		@DisplayName("시작 시간을 마감 기한보다 앞으로 옮기면서 마감 기한을 그대로 두면 400 INVALID_VOTE_DEADLINE이다")
		void startBeforeDeadline() throws Exception {
			Map<String, Object> created = votingBody("자유 연습");
			long scheduleId = create(created);

			api.updateSchedule(leader, scheduleId,
					with(updateBody(created), "startAt", "2026-11-24T18:00:00+09:00"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_VOTE_DEADLINE"));
		}

		@Test
		@DisplayName("입력이 규칙에 맞지 않으면 만들기와 같은 400이고 바뀌지 않는다")
		void invalidInput() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));

			api.updateSchedule(leader, scheduleId, updateBody(scheduleBody("연습 😀")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_TITLE"));

			api.schedule(leader, scheduleId).andExpect(jsonPath("$.title").value("자유 연습"));
		}

		@Test
		@DisplayName("필수와 선택 참여자에게 SCHEDULE_UPDATED 알림 이벤트가 남고, 고친 팀장 본인과 제외된 사람에게는 남지 않는다")
		void notification() throws Exception {
			long scheduleId = create(with(scheduleBody("공연팀 연습"), "requiredAccountIds", List.of(memberA)));

			api.updateSchedule(leader, scheduleId, updateBody(scheduleBody("공연팀 연습(시간 변경)")))
				.andExpect(status().isOk());

			assertThat(api.notified("SCHEDULE_UPDATED")).containsExactly(memberA);
			assertThat(notifiedTitle("SCHEDULE_UPDATED")).isEqualTo("공연팀 연습(시간 변경)");
		}

	}

	@Nested
	@DisplayName("삭제")
	class Delete {

		@Test
		@DisplayName("팀장이 삭제하면 204이고, 일정과 참여자 행이 지워진다. 다시 삭제하거나 조회하면 404다")
		void delete() throws Exception {
			long scheduleId = create(votingBody("자유 연습"));

			api.deleteSchedule(leader, scheduleId).andExpect(status().isNoContent());

			api.schedule(memberA, scheduleId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
			api.deleteSchedule(leader, scheduleId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
			assertThat(api.countSchedules(teamId)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_participant WHERE schedule_id = ?",
					Integer.class, scheduleId)).isZero();
		}

		@Test
		@DisplayName("팀장이 아니면 403, 팀원이 아니면 404이고 지워지지 않는다")
		void permission() throws Exception {
			long scheduleId = create(scheduleBody("자유 연습"));

			api.deleteSchedule(memberA, scheduleId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.deleteSchedule(outsider, scheduleId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));

			assertThat(api.countSchedules(teamId)).isEqualTo(1);
		}

		@Test
		@DisplayName("삭제 직전의 필수와 선택 참여자에게 제목을 담은 SCHEDULE_DELETED 알림 이벤트가 남는다. 팀장 본인은 빠진다")
		void notification() throws Exception {
			long scheduleId = create(votingBody("자유 연습"));
			api.changeStatus(leader, scheduleId, memberA, "REQUIRED").andExpect(status().isOk());

			api.deleteSchedule(leader, scheduleId).andExpect(status().isNoContent());

			assertThat(api.notified("SCHEDULE_DELETED")).containsExactly(memberA, memberB);
			assertThat(notifiedTitle("SCHEDULE_DELETED")).isEqualTo("자유 연습");
		}

	}

	private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
		body.put(key, value);
		return body;
	}

	private static Map<String, Object> without(Map<String, Object> body, String key) {
		body.remove(key);
		return body;
	}

}
