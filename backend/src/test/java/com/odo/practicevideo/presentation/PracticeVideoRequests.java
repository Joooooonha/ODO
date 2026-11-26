package com.odo.practicevideo.presentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.odo.auth.application.AccessTokenIssuer;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.MutableClock;
import com.odo.support.TestAccounts;
import com.odo.support.TestTeams;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연습 영상 통합 테스트가 같이 쓰는 준비와 요청 모음. 팀, 공연, 곡, 영상은 API로 만들고, 팀원은 TestTeams로 DB에 바로 넣는다.
 * 브라우저가 R2에 올리는 것은 InMemoryObjectStorage.uploadAsBrowser로 흉내 낸다.
 */
class PracticeVideoRequests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 업로드 본문의 기본 파일 크기(500MB). */
	static final long FILE_SIZE = 524_288_000L;

	static final String MP4 = "video/mp4";

	private final MockMvc mockMvc;

	private final AccessTokenIssuer accessTokenIssuer;

	private final JdbcTemplate jdbc;

	private final InMemoryObjectStorage storage;

	PracticeVideoRequests(MockMvc mockMvc, AccessTokenIssuer accessTokenIssuer, JdbcTemplate jdbc,
			InMemoryObjectStorage storage) {
		this.mockMvc = mockMvc;
		this.accessTokenIssuer = accessTokenIssuer;
		this.jdbc = jdbc;
		this.storage = storage;
	}

	/**
	 * 테스트마다 쓰는 사람과 공연.
	 * @param leader 팀장, 공연의 디렉터
	 * @param choreographer 디렉터. 영상을 주로 올린다
	 * @param dancer 공연 명단의 팀원(MEMBER)
	 * @param bystander 팀원이지만 공연 명단에 없다
	 * @param leaderSong 팀장이 담당하는 곡
	 * @param choreoSong choreographer가 담당하는 곡
	 */
	record Fixture(long leader, long choreographer, long dancer, long bystander, long teamId, long performanceId,
			long leaderSong, long choreoSong) {
	}

	Fixture setUpFixture(MutableClock clock) throws Exception {
		long leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		long choreographer = TestAccounts.create(jdbc, "이민호", clock.instant());
		long dancer = TestAccounts.create(jdbc, "김지수", clock.instant());
		long bystander = TestAccounts.create(jdbc, "최유나", clock.instant());
		long teamId = createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, choreographer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, dancer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, bystander, 26, clock.instant());
		long performanceId = createPerformance(leader, teamId, "2026 정기공연",
				List.of(member(leader, "DIRECTOR"), member(choreographer, "DIRECTOR"), member(dancer, "MEMBER")));
		long leaderSong = createSong(leader, performanceId, "Hype Boy");
		long choreoSong = createSong(choreographer, performanceId, "Supernova");
		return new Fixture(leader, choreographer, dancer, bystander, teamId, performanceId, leaderSong, choreoSong);
	}

	static Map<String, Object> member(long accountId, String role) {
		return Map.of("accountId", accountId, "role", role);
	}

	String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	long createTeam(long leader, String name) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("generation", 24);
		return idOf(send(post("/api/teams"), leader, body).andExpect(status().isCreated()));
	}

	long createPerformance(long leader, long teamId, String name, List<Map<String, Object>> members)
			throws Exception {
		Map<String, Object> body = Map.of("name", name, "members", members);
		return idOf(send(post("/api/teams/{teamId}/performances", teamId), leader, body)
			.andExpect(status().isCreated()));
	}

	long createSong(long director, long performanceId, String name) throws Exception {
		return idOf(send(post("/api/performances/{performanceId}/songs", performanceId), director,
				Map.of("name", name))
			.andExpect(status().isCreated()));
	}

	void deleteSong(long director, long songId) throws Exception {
		mockMvc.perform(MockMvcRequestBuilders.delete("/api/songs/{songId}", songId).header("Authorization", bearer(director)))
			.andExpect(status().isNoContent());
	}

	/** 업로드 시작 본문. 크기는 500MB, 형식은 mp4다. */
	static Map<String, Object> uploadBody(Long songId, String title) {
		Map<String, Object> body = new HashMap<>();
		body.put("songId", songId);
		body.put("title", title);
		body.put("fileSize", FILE_SIZE);
		body.put("contentType", MP4);
		return body;
	}

	ResultActions startUpload(long accountId, long performanceId, Map<String, ?> body) throws Exception {
		return send(post("/api/performances/{performanceId}/practice-videos", performanceId), accountId, body);
	}

	/** 업로드를 시작하고 영상 ID를 돌려준다. 상태는 UPLOADING이다. */
	long startUploadId(long accountId, long performanceId, long songId, String title) throws Exception {
		return videoIdOf(startUpload(accountId, performanceId, uploadBody(songId, title))
			.andExpect(status().isCreated()));
	}

	/** 업로드를 시작하고 받은 업로드 주소로 서명한 그대로 올린 뒤(브라우저 흉내) 영상 ID를 돌려준다. 완료는 부르지 않는다. */
	long startAndUpload(long accountId, long performanceId, long songId, String title) throws Exception {
		ResultActions started = startUpload(accountId, performanceId, uploadBody(songId, title))
			.andExpect(status().isCreated());
		storage.uploadAsBrowser(uploadUrlOf(started));
		return videoIdOf(started);
	}

	/** 업로드를 시작하고, 올리고, 완료해 READY인 영상의 ID를 돌려준다. */
	long uploadReady(long accountId, long performanceId, long songId, String title) throws Exception {
		long id = startAndUpload(accountId, performanceId, songId, title);
		complete(accountId, id).andExpect(status().isOk());
		return id;
	}

	ResultActions complete(long accountId, long videoId) throws Exception {
		return mockMvc.perform(post("/api/practice-videos/{id}/upload-completion", videoId)
			.header("Authorization", bearer(accountId)));
	}

	ResultActions list(long accountId, long performanceId, Map<String, String> params) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/performances/{performanceId}/practice-videos",
				performanceId)
			.header("Authorization", bearer(accountId));
		params.forEach(request::queryParam);
		return mockMvc.perform(request);
	}

	ResultActions list(long accountId, long performanceId) throws Exception {
		return list(accountId, performanceId, Map.of());
	}

	ResultActions detail(long accountId, long videoId) throws Exception {
		return mockMvc
			.perform(get("/api/practice-videos/{id}", videoId).header("Authorization", bearer(accountId)));
	}

	ResultActions update(long accountId, long videoId, String title, String description) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		body.put("description", description);
		return send(put("/api/practice-videos/{id}", videoId), accountId, body);
	}

	ResultActions delete(long accountId, long videoId) throws Exception {
		return mockMvc
			.perform(MockMvcRequestBuilders.delete("/api/practice-videos/{id}", videoId).header("Authorization", bearer(accountId)));
	}

	/** DB의 영상 상태. 행이 없으면 null. */
	String statusOf(long videoId) {
		List<String> statuses = jdbc.queryForList("SELECT status FROM practice_video WHERE id = ?", String.class,
				videoId);
		return statuses.isEmpty() ? null : statuses.get(0);
	}

	String objectKeyOf(long videoId) {
		return jdbc.queryForObject("SELECT object_key FROM practice_video WHERE id = ?", String.class, videoId);
	}

	/** 명단의 역할을 DB에서 바로 바꾼다. 1차에는 디렉터 지정 해제 API가 없다. */
	void setRole(long performanceId, long accountId, String role) {
		jdbc.update("UPDATE performance_member SET role = ? WHERE performance_id = ? AND account_id = ?", role,
				performanceId, accountId);
	}

	static String uploadUrlOf(ResultActions result) throws Exception {
		return JsonPath.read(body(result), "$.upload.url");
	}

	static long videoIdOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(body(result), "$.video.id")).longValue();
	}

	static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** 공연 명단에 사람을 넣는다(팀장). 팀원이어야 한다. */
	void addToRoster(long leader, long performanceId, List<Map<String, Object>> members) throws Exception {
		send(post("/api/performances/{performanceId}/members", performanceId), leader, Map.of("members", members))
			.andExpect(status().isCreated());
	}

	ResultActions recordView(long accountId, long videoId) throws Exception {
		return mockMvc
			.perform(put("/api/practice-videos/{id}/views/me", videoId).header("Authorization", bearer(accountId)));
	}

	ResultActions views(long accountId, long videoId) throws Exception {
		return mockMvc.perform(get("/api/practice-videos/{id}/views", videoId).header("Authorization", bearer(accountId)));
	}

	ResultActions play(long accountId, long videoId) throws Exception {
		return mockMvc.perform(post("/api/practice-videos/{id}/plays", videoId).header("Authorization", bearer(accountId)));
	}

	ResultActions threads(long accountId, long videoId) throws Exception {
		return mockMvc
			.perform(get("/api/practice-videos/{id}/threads", videoId).header("Authorization", bearer(accountId)));
	}

	/** 스레드 만들기. positionSeconds가 null이면 본문에서 뺀다. */
	ResultActions createThread(long accountId, long videoId, Integer positionSeconds, String content)
			throws Exception {
		Map<String, Object> body = new HashMap<>();
		if (positionSeconds != null) {
			body.put("positionSeconds", positionSeconds);
		}
		body.put("content", content);
		return send(post("/api/practice-videos/{id}/threads", videoId), accountId, body);
	}

	/** 스레드를 만들고 스레드 ID를 돌려준다. */
	long createThreadId(long accountId, long videoId, int positionSeconds, String content) throws Exception {
		return idOf(createThread(accountId, videoId, positionSeconds, content).andExpect(status().isCreated()));
	}

	ResultActions reply(long accountId, long threadId, String content) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("content", content);
		return send(post("/api/practice-video-threads/{threadId}/comments", threadId), accountId, body);
	}

	/**
	 * 다른 트랜잭션이 영상 행을 지우고 커밋하지 않은 동안 call을 보내고, call이 잠금을 기다리는 것을 본 뒤 커밋한다.
	 * 영상 삭제가 행 삭제까지 끝내는 순간에 들어온 요청이다. call의 결과를 돌려준다.
	 */
	ResultActions whileVideoRowDeleted(PlatformTransactionManager transactionManager, long videoId,
			Callable<ResultActions> call) throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		CountDownLatch rowDeleted = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> deleting = executor.submit(() -> transaction.executeWithoutResult(status -> {
				jdbc.update("DELETE FROM practice_video WHERE id = ?", videoId);
				rowDeleted.countDown();
				try {
					commit.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(rowDeleted.await(5, TimeUnit.SECONDS)).as("삭제가 영상 행을 지웠다").isTrue();

			Future<ResultActions> calling = executor.submit(call);
			assertThat(waitUntilSomeoneWaitsForLock()).as("요청이 삭제의 커밋을 기다린다").isTrue();

			commit.countDown();
			deleting.get(5, TimeUnit.SECONDS);
			return calling.get(5, TimeUnit.SECONDS);
		}
		finally {
			commit.countDown();
			executor.shutdownNow();
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

	/** 표에 남은 행 수. 영상을 지운 뒤 함께 지워졌는지 볼 때 쓴다. */
	long countRows(String table, long videoId) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE video_id = ?", Long.class, videoId);
		return count == null ? 0 : count;
	}

	private ResultActions send(MockHttpServletRequestBuilder request, long accountId, Map<String, ?> body)
			throws Exception {
		return mockMvc.perform(request.header("Authorization", bearer(accountId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body)));
	}

	private static long idOf(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(body(result), "$.id")).longValue();
	}

}
