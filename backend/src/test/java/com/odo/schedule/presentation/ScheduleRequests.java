package com.odo.schedule.presentation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.odo.auth.application.AccessTokenIssuer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일정 통합 테스트가 같이 쓰는 요청과 준비 모음. 팀, 연습실, 일정은 API로 만든다(팀원은 TestTeams로 DB에 바로 넣는다).
 * 투표 값은 투표 기능(PR C)이 없어서 DB에 바로 넣는다(setVote).
 */
class ScheduleRequests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 기본 시작 시간. 2026-11-25 19:00(한국 시간). 테스트 시계(2026-11-20 18:00)보다 뒤다. */
	static final String START = "2026-11-25T19:00:00+09:00";

	/** START에 기본 연습 시간(120분)을 더한 종료 시간. */
	static final String END = "2026-11-25T21:00:00+09:00";

	/** START 전날 19:00. 투표 마감으로 쓴다. */
	static final String DEADLINE = "2026-11-24T19:00:00+09:00";

	private final MockMvc mockMvc;

	private final AccessTokenIssuer accessTokenIssuer;

	private final JdbcTemplate jdbc;

	ScheduleRequests(MockMvc mockMvc, AccessTokenIssuer accessTokenIssuer, JdbcTemplate jdbc) {
		this.mockMvc = mockMvc;
		this.accessTokenIssuer = accessTokenIssuer;
		this.jdbc = jdbc;
	}

	String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	/** 팀 스페이스를 만들고 팀 ID를 돌려준다. 만든 사람이 팀장이 된다. */
	long createTeam(long leader, String name) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		return idOf(send(post("/api/teams"), leader, body).andExpect(status().isCreated()));
	}

	// 팀원 승인. 새 팀원이 일정에 들어가는지 확인할 때 쓴다.

	String inviteCode(long leader, long teamId) throws Exception {
		String response = body(mockMvc
			.perform(get("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(leader)))
			.andExpect(status().isOk()));
		return JsonPath.read(response, "$.inviteCode");
	}

	long requestJoinId(long applicant, String inviteCode) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("generation", 27);
		String response = body(send(post("/api/invites/{code}/join-requests", inviteCode), applicant, body)
			.andExpect(status().isCreated()));
		return ((Number) JsonPath.read(response, "$.requestId")).longValue();
	}

	ResultActions approve(long leader, long teamId, long requestId) throws Exception {
		return mockMvc.perform(post("/api/teams/{teamId}/join-requests/{requestId}/approve", teamId, requestId)
			.header("Authorization", bearer(leader)));
	}

	// 연습실

	static Map<String, Object> roomBody(String name) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		return body;
	}

	ResultActions createRoom(long accountId, long teamId, Map<String, ?> body) throws Exception {
		return send(post("/api/teams/{teamId}/practice-rooms", teamId), accountId, body);
	}

	long createRoomId(long leader, long teamId, Map<String, ?> body) throws Exception {
		return idOf(createRoom(leader, teamId, body).andExpect(status().isCreated()));
	}

	long createRoomId(long leader, long teamId, String name) throws Exception {
		return createRoomId(leader, teamId, roomBody(name));
	}

	ResultActions listRooms(long accountId, long teamId) throws Exception {
		return mockMvc
			.perform(get("/api/teams/{teamId}/practice-rooms", teamId).header("Authorization", bearer(accountId)));
	}

	ResultActions room(long accountId, long roomId) throws Exception {
		return mockMvc.perform(get("/api/practice-rooms/{id}", roomId).header("Authorization", bearer(accountId)));
	}

	ResultActions updateRoom(long accountId, long roomId, Map<String, ?> body) throws Exception {
		return send(put("/api/practice-rooms/{id}", roomId), accountId, body);
	}

	ResultActions deleteRoom(long accountId, long roomId) throws Exception {
		return mockMvc.perform(delete("/api/practice-rooms/{id}", roomId).header("Authorization", bearer(accountId)));
	}

	// 일정

	/** 투표 없는 일정의 기본 본문. 필요한 값만 바꿔 쓴다. */
	static Map<String, Object> scheduleBody(String title) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		body.put("startAt", START);
		body.put("durationMinutes", 120);
		body.put("votingEnabled", false);
		return body;
	}

	/** 투표 일정의 기본 본문. 마감은 DEADLINE이다. */
	static Map<String, Object> votingBody(String title) {
		Map<String, Object> body = scheduleBody(title);
		body.put("votingEnabled", true);
		body.put("voteDeadline", DEADLINE);
		return body;
	}

	/** 수정 본문. 상세 응답에서 수정할 수 있는 값을 옮겨 담는 대신, 만들 때의 본문에서 만들기 전용 값을 뺀다. */
	static Map<String, Object> updateBody(Map<String, Object> createBody) {
		Map<String, Object> body = new HashMap<>(createBody);
		body.remove("votingEnabled");
		body.remove("requiredAccountIds");
		return body;
	}

	ResultActions createSchedule(long accountId, long teamId, Map<String, ?> body) throws Exception {
		return send(post("/api/teams/{teamId}/schedules", teamId), accountId, body);
	}

	long createScheduleId(long leader, long teamId, Map<String, ?> body) throws Exception {
		return idOf(createSchedule(leader, teamId, body).andExpect(status().isCreated()));
	}

	ResultActions listSchedules(long accountId, long teamId, String query) throws Exception {
		return mockMvc.perform(get("/api/teams/{teamId}/schedules" + query, teamId).header("Authorization",
				bearer(accountId)));
	}

	ResultActions schedule(long accountId, long scheduleId) throws Exception {
		return mockMvc.perform(get("/api/schedules/{id}", scheduleId).header("Authorization", bearer(accountId)));
	}

	ResultActions updateSchedule(long accountId, long scheduleId, Map<String, ?> body) throws Exception {
		return send(put("/api/schedules/{id}", scheduleId), accountId, body);
	}

	ResultActions deleteSchedule(long accountId, long scheduleId) throws Exception {
		return mockMvc.perform(delete("/api/schedules/{id}", scheduleId).header("Authorization", bearer(accountId)));
	}

	// 참여자

	ResultActions participants(long accountId, long scheduleId) throws Exception {
		return mockMvc
			.perform(get("/api/schedules/{id}/participants", scheduleId).header("Authorization", bearer(accountId)));
	}

	ResultActions changeStatus(long accountId, long scheduleId, long target, String status) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("status", status);
		return send(patch("/api/schedules/{id}/participants/{accountId}", scheduleId, target), accountId, body);
	}

	// 투표

	/** 투표를 보낸다. choice가 null이면 본문에 choice를 넣지 않는다. */
	ResultActions vote(long accountId, long scheduleId, String choice) throws Exception {
		Map<String, Object> body = new HashMap<>();
		if (choice != null) {
			body.put("choice", choice);
		}
		return send(post("/api/schedules/{id}/votes", scheduleId), accountId, body);
	}

	/** 투표를 보내고 202를 확인한 뒤 요청 ID를 돌려준다. */
	long voteRequestId(long accountId, long scheduleId, String choice) throws Exception {
		String response = body(vote(accountId, scheduleId, choice).andExpect(status().isAccepted()));
		return ((Number) JsonPath.read(response, "$.requestId")).longValue();
	}

	ResultActions voteResult(long accountId, long requestId) throws Exception {
		return mockMvc
			.perform(get("/api/vote-requests/{requestId}", requestId).header("Authorization", bearer(accountId)));
	}

	/** 요청 테이블의 처리 상태(PENDING, CONFIRMED, FULL, INVALID). */
	String requestStatusOf(long requestId) {
		return jdbc.queryForObject("SELECT status FROM vote_request WHERE id = ?", String.class, requestId);
	}

	String requestReasonOf(long requestId) {
		return jdbc.queryForObject("SELECT reason FROM vote_request WHERE id = ?", String.class, requestId);
	}

	boolean reorderedOf(long requestId) {
		return jdbc.queryForObject("SELECT reordered FROM vote_request WHERE id = ?", Boolean.class, requestId);
	}

	int countVoteRequests() {
		return jdbc.queryForObject("SELECT count(*) FROM vote_request", Integer.class);
	}

	/** 그 일정에서 참여로 투표한 선택 참여자 수. */
	int countYes(long scheduleId) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM schedule_participant WHERE schedule_id = ? AND status = 'OPTIONAL' AND vote = 'YES'",
				Integer.class, scheduleId);
	}

	// DB 확인과 준비

	/** 투표 기능 없이 참여자의 투표 값을 넣는다. 선택 참여자에게만 쓴다(DB check). */
	void setVote(long scheduleId, long accountId, String vote, Instant votedAt) {
		jdbc.update("UPDATE schedule_participant SET vote = ?, voted_at = ? WHERE schedule_id = ? AND account_id = ?", vote,
				Timestamp.from(votedAt), scheduleId, accountId);
	}

	String statusOf(long scheduleId, long accountId) {
		return jdbc.queryForObject("SELECT status FROM schedule_participant WHERE schedule_id = ? AND account_id = ?",
				String.class, scheduleId, accountId);
	}

	String voteOf(long scheduleId, long accountId) {
		return jdbc.queryForObject("SELECT vote FROM schedule_participant WHERE schedule_id = ? AND account_id = ?",
				String.class, scheduleId, accountId);
	}

	int countParticipantRows(long scheduleId, long accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM schedule_participant WHERE schedule_id = ? AND account_id = ?",
				Integer.class, scheduleId, accountId);
	}

	int countSchedules(long teamId) {
		return jdbc.queryForObject("SELECT count(*) FROM schedule WHERE team_id = ?", Integer.class, teamId);
	}

	/** 그 종류의 알림 이벤트를 받은 사람. 받는 사람 ID 순이다. */
	List<Long> notified(String type) {
		return jdbc.queryForList(
				"SELECT recipient_account_id FROM notification_event WHERE type = ? ORDER BY recipient_account_id",
				Long.class, type);
	}

	int countNotifications() {
		return jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class);
	}

	// 응답 읽기

	static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** 응답의 시각을 Instant로 읽는다. 오프셋 표기(+09:00, Z)와 관계없이 같은 순간인지 비교하기 위해서다. */
	static Instant instantAt(ResultActions result, String path) throws Exception {
		String value = JsonPath.read(body(result), path);
		return OffsetDateTime.parse(value).toInstant();
	}

	static Instant instant(String isoWithOffset) {
		return OffsetDateTime.parse(isoWithOffset).toInstant();
	}

	static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(body(result), "$.id")).longValue();
	}

	private ResultActions send(MockHttpServletRequestBuilder request, long accountId, Map<String, ?> body)
			throws Exception {
		return mockMvc.perform(request.header("Authorization", bearer(accountId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body)));
	}

}
