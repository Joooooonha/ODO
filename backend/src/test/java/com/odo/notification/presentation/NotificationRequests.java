package com.odo.notification.presentation;

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
 * 알림 통합 테스트가 같이 쓰는 요청과 준비 모음. 알림은 각 기능의 API로 만든다(팀원은 TestTeams로 DB에 바로 넣는다).
 * 11_Notification_spec.md의 "테스트 방법".
 */
class NotificationRequests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 기본 일정 시작. 테스트 시계(2026-11-20 18:00 한국 시간)보다 뒤다. */
	static final String START = "2026-11-25T19:00:00+09:00";

	/** START 전날 19:00. 투표 마감으로 쓴다. */
	static final String DEADLINE = "2026-11-24T19:00:00+09:00";

	private final MockMvc mockMvc;

	private final AccessTokenIssuer accessTokenIssuer;

	private final JdbcTemplate jdbc;

	NotificationRequests(MockMvc mockMvc, AccessTokenIssuer accessTokenIssuer, JdbcTemplate jdbc) {
		this.mockMvc = mockMvc;
		this.accessTokenIssuer = accessTokenIssuer;
		this.jdbc = jdbc;
	}

	String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	// 팀 스페이스

	/** 팀 스페이스를 만들고 팀 ID를 돌려준다. 만든 사람이 팀장이 된다. */
	long createTeam(long leader, String name) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		return idOf(send(post("/api/teams"), leader, body).andExpect(status().isCreated()));
	}

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

	ResultActions reject(long leader, long teamId, long requestId) throws Exception {
		return mockMvc.perform(post("/api/teams/{teamId}/join-requests/{requestId}/reject", teamId, requestId)
			.header("Authorization", bearer(leader)));
	}

	ResultActions cancelJoin(long applicant, long requestId) throws Exception {
		return mockMvc
			.perform(delete("/api/join-requests/{requestId}", requestId).header("Authorization", bearer(applicant)));
	}

	// 공연

	static Map<String, Object> director(long accountId) {
		return Map.of("accountId", accountId, "role", "DIRECTOR");
	}

	static Map<String, Object> member(long accountId) {
		return Map.of("accountId", accountId, "role", "MEMBER");
	}

	long createPerformanceId(long leader, long teamId, String name, List<Map<String, Object>> members)
			throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("members", members);
		return idOf(send(post("/api/teams/{teamId}/performances", teamId), leader, body)
			.andExpect(status().isCreated()));
	}

	ResultActions addMembers(long leader, long performanceId, List<Map<String, Object>> members) throws Exception {
		return send(post("/api/performances/{performanceId}/members", performanceId), leader,
				Map.of("members", members));
	}

	ResultActions designateDirector(long leader, long performanceId, long target) throws Exception {
		return send(patch("/api/performances/{performanceId}/members/{accountId}", performanceId, target), leader,
				Map.of("role", "DIRECTOR"));
	}

	ResultActions renamePerformance(long leader, long performanceId, String name) throws Exception {
		return send(put("/api/performances/{performanceId}", performanceId), leader, Map.of("name", name));
	}

	// 일정

	/** 투표 일정. 팀원 모두가 선택 참여자라 팀장을 뺀 팀원 모두가 알림을 받는다. */
	static Map<String, Object> votingBody(String title) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		body.put("startAt", START);
		body.put("durationMinutes", 120);
		body.put("votingEnabled", true);
		body.put("voteDeadline", DEADLINE);
		return body;
	}

	long createScheduleId(long leader, long teamId, String title) throws Exception {
		return idOf(send(post("/api/teams/{teamId}/schedules", teamId), leader, votingBody(title))
			.andExpect(status().isCreated()));
	}

	ResultActions updateSchedule(long leader, long scheduleId, String title) throws Exception {
		Map<String, Object> body = votingBody(title);
		body.remove("votingEnabled");
		return send(put("/api/schedules/{id}", scheduleId), leader, body);
	}

	ResultActions deleteSchedule(long leader, long scheduleId) throws Exception {
		return mockMvc.perform(delete("/api/schedules/{id}", scheduleId).header("Authorization", bearer(leader)));
	}

	ResultActions changeStatus(long leader, long scheduleId, long target, String status) throws Exception {
		return send(patch("/api/schedules/{id}/participants/{accountId}", scheduleId, target), leader,
				Map.of("status", status));
	}

	// 계정

	ResultActions rename(long accountId, String name) throws Exception {
		return send(patch("/api/me"), accountId, Map.of("name", name));
	}

	// 알림

	ResultActions list(long accountId) throws Exception {
		return mockMvc.perform(get("/api/notifications").header("Authorization", bearer(accountId)));
	}

	ResultActions list(long accountId, String cursor) throws Exception {
		return mockMvc.perform(
				get("/api/notifications").param("cursor", cursor).header("Authorization", bearer(accountId)));
	}

	ResultActions deleteOne(long accountId, long notificationId) throws Exception {
		return mockMvc.perform(
				delete("/api/notifications/{id}", notificationId).header("Authorization", bearer(accountId)));
	}

	/** upTo가 null이면 쿼리에 넣지 않는다. */
	ResultActions clear(long accountId, String upTo) throws Exception {
		MockHttpServletRequestBuilder request = delete("/api/notifications").header("Authorization",
				bearer(accountId));
		if (upTo != null) {
			request.param("upTo", upTo);
		}
		return mockMvc.perform(request);
	}

	ResultActions unseen(long accountId) throws Exception {
		return mockMvc.perform(get("/api/notifications/unseen").header("Authorization", bearer(accountId)));
	}

	ResultActions opened(long accountId, Map<String, ?> body) throws Exception {
		return send(put("/api/notifications/opened"), accountId, body);
	}

	ResultActions opened(long accountId, long seq) throws Exception {
		return opened(accountId, Map.of("seq", seq));
	}

	boolean hasNew(long accountId) throws Exception {
		return JsonPath.read(body(unseen(accountId).andExpect(status().isOk())), "$.hasNew");
	}

	// DB 확인

	/** 받은 알림의 (type, seq)를 순번 순으로. */
	List<Map<String, Object>> rowsOf(long recipient) {
		return jdbc.queryForList(
				"SELECT id, seq, type, team_id, actor_account_id, target_type, target_id, payload::text AS payload "
						+ "FROM notification WHERE recipient_account_id = ? ORDER BY seq",
				recipient);
	}

	List<String> typesOf(long recipient) {
		return jdbc.queryForList("SELECT type FROM notification WHERE recipient_account_id = ? ORDER BY seq",
				String.class, recipient);
	}

	List<Long> seqsOf(long recipient) {
		return jdbc.queryForList("SELECT seq FROM notification WHERE recipient_account_id = ? ORDER BY seq",
				Long.class, recipient);
	}

	/** 알림함 상태 행의 마지막 순번. 행이 없으면 0이다. */
	long lastSeqOf(long accountId) {
		List<Long> values = jdbc.queryForList("SELECT last_seq FROM notification_inbox WHERE account_id = ?",
				Long.class, accountId);
		return values.isEmpty() ? 0 : values.getFirst();
	}

	long notificationIdOf(long recipient, long seq) {
		return jdbc.queryForObject("SELECT id FROM notification WHERE recipient_account_id = ? AND seq = ?",
				Long.class, recipient, seq);
	}

	// 응답 읽기

	static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
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
