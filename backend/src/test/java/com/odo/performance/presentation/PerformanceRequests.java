package com.odo.performance.presentation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.odo.auth.application.AccessTokenIssuer;
import org.springframework.http.MediaType;
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
 * 공연 통합 테스트가 같이 쓰는 요청 모음. 팀과 공연은 API로 만든다(팀원은 TestTeams로 DB에 바로 넣는다).
 */
class PerformanceRequests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MockMvc mockMvc;

	private final AccessTokenIssuer accessTokenIssuer;

	PerformanceRequests(MockMvc mockMvc, AccessTokenIssuer accessTokenIssuer) {
		this.mockMvc = mockMvc;
		this.accessTokenIssuer = accessTokenIssuer;
	}

	static Map<String, Object> entry(Long accountId, String role) {
		Map<String, Object> entry = new HashMap<>();
		entry.put("accountId", accountId);
		entry.put("role", role);
		return entry;
	}

	static Map<String, Object> director(long accountId) {
		return entry(accountId, "DIRECTOR");
	}

	static Map<String, Object> member(long accountId) {
		return entry(accountId, "MEMBER");
	}

	String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	/** 팀 스페이스를 만들고 팀 ID를 돌려준다. 만든 사람이 그 기수로 팀장이 된다. */
	long createTeam(long leader, String name, Integer generation) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("generation", generation);
		return idOf(send(post("/api/teams"), leader, body).andExpect(status().isCreated()));
	}

	ResultActions createPerformance(long accountId, long teamId, Map<String, ?> body) throws Exception {
		return send(post("/api/teams/{teamId}/performances", teamId), accountId, body);
	}

	@SafeVarargs
	final long createPerformanceId(long leader, long teamId, String name, Map<String, Object>... members)
			throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("members", Arrays.asList(members));
		return idOf(createPerformance(leader, teamId, body).andExpect(status().isCreated()));
	}

	ResultActions listPerformances(long accountId, long teamId) throws Exception {
		return mockMvc
			.perform(get("/api/teams/{teamId}/performances", teamId).header("Authorization", bearer(accountId)));
	}

	ResultActions performanceDetail(long accountId, long performanceId) throws Exception {
		return mockMvc
			.perform(get("/api/performances/{performanceId}", performanceId).header("Authorization", bearer(accountId)));
	}

	ResultActions updatePerformance(long accountId, long performanceId, Map<String, ?> body) throws Exception {
		return send(put("/api/performances/{performanceId}", performanceId), accountId, body);
	}

	ResultActions listMembers(long accountId, long performanceId) throws Exception {
		return mockMvc.perform(get("/api/performances/{performanceId}/members", performanceId)
			.header("Authorization", bearer(accountId)));
	}

	ResultActions addMembersBody(long accountId, long performanceId, Map<String, ?> body) throws Exception {
		return send(post("/api/performances/{performanceId}/members", performanceId), accountId, body);
	}

	@SafeVarargs
	final ResultActions addMembers(long accountId, long performanceId, Map<String, Object>... members)
			throws Exception {
		return addMembersBody(accountId, performanceId, Map.of("members", List.of(members)));
	}

	ResultActions designate(long accountId, long performanceId, long target, Map<String, ?> body) throws Exception {
		return send(patch("/api/performances/{performanceId}/members/{accountId}", performanceId, target), accountId,
				body);
	}

	ResultActions listSongs(long accountId, long performanceId) throws Exception {
		return mockMvc.perform(
				get("/api/performances/{performanceId}/songs", performanceId).header("Authorization", bearer(accountId)));
	}

	ResultActions createSong(long accountId, long performanceId, String name) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		return send(post("/api/performances/{performanceId}/songs", performanceId), accountId, body);
	}

	long createSongId(long accountId, long performanceId, String name) throws Exception {
		return idOf(createSong(accountId, performanceId, name).andExpect(status().isCreated()));
	}

	ResultActions song(long accountId, long songId) throws Exception {
		return mockMvc.perform(get("/api/songs/{songId}", songId).header("Authorization", bearer(accountId)));
	}

	ResultActions renameSong(long accountId, long songId, String name) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		return send(put("/api/songs/{songId}", songId), accountId, body);
	}

	ResultActions deleteSong(long accountId, long songId) throws Exception {
		return mockMvc.perform(delete("/api/songs/{songId}", songId).header("Authorization", bearer(accountId)));
	}

	private ResultActions send(MockHttpServletRequestBuilder request, long accountId, Map<String, ?> body) throws Exception {
		return mockMvc.perform(request.header("Authorization", bearer(accountId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body)));
	}

	private static long idOf(ResultActions result) throws Exception {
		String body = result.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

}
