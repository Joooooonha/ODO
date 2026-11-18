package com.odo.team.presentation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초대 확인, 입장 요청 보내기와 취소, 대기 목록, 승인과 거절, 초대 코드 새로 발급, 동시 요청.
 * 02_TeamSpace_spec.md의 "초대와 입장 (통합)".
 */
@IntegrationTest
class JoinRequestIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int CONCURRENT_ROUNDS = 10;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private long leader;

	private long applicant;

	private long teamId;

	private String inviteCode;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		applicant = TestAccounts.create(jdbc, "김지수", clock.instant());
		teamId = createTeam(leader, "블랙홀", "매주 화목 연습하는 힙합 팀입니다");
		inviteCode = inviteCodeOf(teamId);
	}

	@Nested
	@DisplayName("초대 확인")
	class Preview {

		@Test
		@DisplayName("팀 이름, 팀장 이름, 소개 문구와 내 상태 NONE을 받는다")
		void none() throws Exception {
			preview(applicant, inviteCode).andExpect(status().isOk())
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.teamName").value("블랙홀"))
				.andExpect(jsonPath("$.leaderName").value("박준하"))
				.andExpect(jsonPath("$.description").value("매주 화목 연습하는 힙합 팀입니다"))
				.andExpect(jsonPath("$.myStatus").value("NONE"))
				.andExpect(jsonPath("$.myRequestId").value(nullValue()));
		}

		@Test
		@DisplayName("대기 중인 요청이 있으면 PENDING과 그 요청 ID를 받는다")
		void pending() throws Exception {
			long requestId = requestJoinId(applicant, 25);

			preview(applicant, inviteCode).andExpect(jsonPath("$.myStatus").value("PENDING"))
				.andExpect(jsonPath("$.myRequestId").value(requestId));
		}

		@Test
		@DisplayName("이미 팀원이면 MEMBER를 받는다")
		void member() throws Exception {
			preview(leader, inviteCode).andExpect(jsonPath("$.myStatus").value("MEMBER"));
		}

		@Test
		@DisplayName("없는 코드면 404 INVITE_NOT_FOUND다")
		void unknownCode() throws Exception {
			preview(applicant, "AAAAAAAAAAAAAAAAAAAAAA").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		}

		@Test
		@DisplayName("로그인하지 않으면 401이다")
		void unauthenticated() throws Exception {
			mockMvc.perform(get("/api/invites/{code}", inviteCode)).andExpect(status().isUnauthorized());
		}

	}

	@Nested
	@DisplayName("입장 요청 보내기")
	class Request {

		@Test
		@DisplayName("요청을 보내면 201이고, 팀장의 대기 목록에 기수와 함께 보이며, 승인 전에는 팀 상세가 404다")
		void request() throws Exception {
			requestJoin(applicant, 25).andExpect(status().isCreated())
				.andExpect(jsonPath("$.requestId").isNumber())
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.teamName").value("블랙홀"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.requestedAt").isNotEmpty());

			pendingList(leader).andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].accountId").value(applicant))
				.andExpect(jsonPath("$[0].name").value("김지수"))
				.andExpect(jsonPath("$[0].generation").value(25))
				.andExpect(jsonPath("$[0].requestedAt").isNotEmpty());
			teamDetail(applicant).andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("기수 없이 요청할 수 있다")
		void withoutGeneration() throws Exception {
			requestJoin(applicant, null).andExpect(status().isCreated());

			pendingList(leader).andExpect(jsonPath("$[0].generation").value(nullValue()));
		}

		@Test
		@DisplayName("기수가 1~999가 아니면 400 INVALID_GENERATION이고 요청이 만들어지지 않는다")
		void invalidGeneration() throws Exception {
			requestJoin(applicant, 1000).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_GENERATION"));

			pendingList(leader).andExpect(jsonPath("$", hasSize(0)));
		}

		@Test
		@DisplayName("이미 팀원이면 409 ALREADY_MEMBER다")
		void alreadyMember() throws Exception {
			requestJoin(leader, null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
		}

		@Test
		@DisplayName("대기 중인 요청이 있으면 409 ALREADY_REQUESTED이고 요청은 하나만 남는다")
		void alreadyRequested() throws Exception {
			requestJoin(applicant, 25).andExpect(status().isCreated());

			requestJoin(applicant, 26).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_REQUESTED"));
			pendingList(leader).andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].generation").value(25));
		}

		@Test
		@DisplayName("없는 코드로 요청하면 404 INVITE_NOT_FOUND다")
		void unknownCode() throws Exception {
			requestJoin(applicant, "AAAAAAAAAAAAAAAAAAAAAA", null).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		}

		@Test
		@DisplayName("내 요청 목록에는 대기 중인 요청만 보인다")
		void myRequests() throws Exception {
			long other = createTeam(TestAccounts.create(jdbc, "이민호", clock.instant()), "화이트홀", null);
			long first = requestJoinId(applicant, 25);
			long second = requestJoinId(applicant, inviteCodeOf(other), null);
			approve(leader, teamId, first).andExpect(status().isOk());

			mockMvc.perform(get("/api/join-requests/me").header("Authorization", bearer(applicant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].requestId").value(second))
				.andExpect(jsonPath("$[0].teamId").value(other))
				.andExpect(jsonPath("$[0].teamName").value("화이트홀"))
				.andExpect(jsonPath("$[0].status").value("PENDING"));
		}

	}

	@Nested
	@DisplayName("대기 목록, 승인, 거절, 취소")
	class Decide {

		@Test
		@DisplayName("대기 목록은 대기 중인 요청만 오래된 것부터 보인다")
		void pendingOrder() throws Exception {
			long second = TestAccounts.create(jdbc, "이민호", clock.instant());
			long third = TestAccounts.create(jdbc, "최유나", clock.instant());
			requestJoinId(applicant, null);
			clock.advance(Duration.ofMinutes(1));
			long rejected = requestJoinId(second, null);
			clock.advance(Duration.ofMinutes(1));
			requestJoinId(third, null);
			reject(leader, teamId, rejected).andExpect(status().isNoContent());

			pendingList(leader).andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].accountId").value(applicant))
				.andExpect(jsonPath("$[1].accountId").value(third));
		}

		@Test
		@DisplayName("승인하면 요청한 기수로 팀원이 되어 팀원 목록과 그 사람의 내 팀 목록에 보이고, 대기 목록에서 빠진다")
		void approveRequest() throws Exception {
			long requestId = requestJoinId(applicant, 25);

			approve(leader, teamId, requestId).andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(applicant))
				.andExpect(jsonPath("$.name").value("김지수"))
				.andExpect(jsonPath("$.generation").value(25))
				.andExpect(jsonPath("$.role").value("MEMBER"));

			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(applicant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[1].accountId").value(applicant))
				.andExpect(jsonPath("$[1].generation").value(25));
			mockMvc.perform(get("/api/teams").header("Authorization", bearer(applicant)))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(teamId))
				.andExpect(jsonPath("$[0].myRole").value("MEMBER"));
			teamDetail(applicant).andExpect(status().isOk());
			pendingList(leader).andExpect(jsonPath("$", hasSize(0)));
		}

		@Test
		@DisplayName("거절하면 204이고 대기 목록에서 빠지며 팀원이 되지 않고, 같은 링크로 다시 요청할 수 있다")
		void rejectRequest() throws Exception {
			long requestId = requestJoinId(applicant, 25);

			reject(leader, teamId, requestId).andExpect(status().isNoContent());

			pendingList(leader).andExpect(jsonPath("$", hasSize(0)));
			teamDetail(applicant).andExpect(status().isNotFound());
			preview(applicant, inviteCode).andExpect(jsonPath("$.myStatus").value("NONE"));
			requestJoin(applicant, 25).andExpect(status().isCreated());
		}

		@Test
		@DisplayName("요청한 사람이 취소하면 204이고 대기 목록에서 빠지며, 같은 링크로 다시 요청할 수 있다")
		void cancelRequest() throws Exception {
			long requestId = requestJoinId(applicant, 25);

			cancel(applicant, requestId).andExpect(status().isNoContent());

			pendingList(leader).andExpect(jsonPath("$", hasSize(0)));
			requestJoin(applicant, 25).andExpect(status().isCreated());
		}

		@Test
		@DisplayName("남의 요청을 취소하면 404 REQUEST_NOT_FOUND이고 요청은 그대로다")
		void cancelOthers() throws Exception {
			long requestId = requestJoinId(applicant, 25);

			cancel(leader, requestId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
			pendingList(leader).andExpect(jsonPath("$", hasSize(1)));
		}

		@Test
		@DisplayName("이미 처리된 요청을 승인, 거절, 취소하면 409 REQUEST_NOT_PENDING이다")
		void notPending() throws Exception {
			long rejected = requestJoinId(applicant, null);
			reject(leader, teamId, rejected).andExpect(status().isNoContent());
			long canceled = requestJoinId(applicant, null);
			cancel(applicant, canceled).andExpect(status().isNoContent());
			long approved = requestJoinId(applicant, null);
			approve(leader, teamId, approved).andExpect(status().isOk());

			approve(leader, teamId, rejected).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_PENDING"));
			approve(leader, teamId, canceled).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_PENDING"));
			reject(leader, teamId, approved).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_PENDING"));
			cancel(applicant, approved).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_PENDING"));
			assertThat(countMembers(teamId)).isEqualTo(2);
		}

		@Test
		@DisplayName("다른 팀의 요청을 이 팀 주소로 승인하거나 거절하면 404 REQUEST_NOT_FOUND다")
		void otherTeamsRequest() throws Exception {
			long otherLeader = TestAccounts.create(jdbc, "이민호", clock.instant());
			long otherTeam = createTeam(otherLeader, "화이트홀", null);
			long requestId = requestJoinId(applicant, inviteCodeOf(otherTeam), null);

			approve(leader, teamId, requestId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
			reject(leader, teamId, requestId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
			assertThat(countMembers(teamId)).isEqualTo(1);
		}

		@Test
		@DisplayName("팀장이 아닌 팀원이 승인하거나 거절하면 403, 팀원이 아닌 사람이면 404다")
		void notLeader() throws Exception {
			long member = TestAccounts.create(jdbc, "이민호", clock.instant());
			TestTeams.addMember(jdbc, teamId, member, null, clock.instant());
			long requestId = requestJoinId(applicant, null);

			approve(member, teamId, requestId).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			reject(member, teamId, requestId).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			approve(applicant, teamId, requestId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
			pendingList(leader).andExpect(jsonPath("$", hasSize(1)));
		}

	}

	@Nested
	@DisplayName("초대 코드 새로 발급")
	class Regenerate {

		@Test
		@DisplayName("이전 코드로는 초대 확인과 요청이 404 INVITE_NOT_FOUND이고, 이미 있던 대기 중인 요청은 남아 승인할 수 있다")
		void oldCodeInvalid() throws Exception {
			long requestId = requestJoinId(applicant, 25);
			long latecomer = TestAccounts.create(jdbc, "이민호", clock.instant());

			mockMvc.perform(post("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(leader)))
				.andExpect(status().isOk());

			preview(latecomer, inviteCode).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
			requestJoin(latecomer, inviteCode, null).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
			pendingList(leader).andExpect(jsonPath("$", hasSize(1)));
			approve(leader, teamId, requestId).andExpect(status().isOk());
			requestJoin(latecomer, inviteCodeOf(teamId), null).andExpect(status().isCreated());
		}

	}

	@Nested
	@DisplayName("동시 요청")
	class Concurrency {

		@Test
		@DisplayName("같은 사람이 같은 팀에 동시에 요청해도 대기 중인 요청은 하나만 생기고, 나머지는 409 ALREADY_REQUESTED다")
		void concurrentRequests() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long account = TestAccounts.create(jdbc, "동시요청", clock.instant());

				List<MockHttpServletResponse> responses = concurrently(4, () -> requestJoin(account, 25));

				assertThat(statuses(responses)).as("round %d", round).containsOnly(201, 409);
				assertThat(statuses(responses).stream().filter(s -> s == 201)).as("round %d", round).hasSize(1);
				assertThat(responses.stream().filter(r -> r.getStatus() == 409))
					.allSatisfy(r -> assertThat(JsonPath.<String>read(r.getContentAsString(), "$.code"))
						.isEqualTo("ALREADY_REQUESTED"));
				assertThat(countPending(account)).as("round %d", round).isEqualTo(1);
			}
		}

		@Test
		@DisplayName("같은 요청을 동시에 두 번 승인해도 한 번만 반영되어 팀원 행은 하나이고, 나머지는 409 REQUEST_NOT_PENDING이다")
		void concurrentApprovals() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long account = TestAccounts.create(jdbc, "동시승인", clock.instant());
				long requestId = requestJoinId(account, null);

				List<MockHttpServletResponse> responses = concurrently(2, () -> approve(leader, teamId, requestId));

				assertThat(statuses(responses)).as("round %d", round).containsExactlyInAnyOrder(200, 409);
				assertThat(countMembership(account)).as("round %d", round).isEqualTo(1);
			}
		}

		@Test
		@DisplayName("같은 요청에 승인과 취소가 동시에 와도 하나만 반영되고, 승인이 반영됐을 때만 팀원이 된다")
		void approveAndCancel() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long account = TestAccounts.create(jdbc, "승인취소", clock.instant());
				long requestId = requestJoinId(account, null);

				List<MockHttpServletResponse> responses = concurrently(List.of(() -> approve(leader, teamId, requestId),
						() -> cancel(account, requestId)));

				int approveStatus = responses.get(0).getStatus();
				int cancelStatus = responses.get(1).getStatus();
				String finalStatus = jdbc.queryForObject("SELECT status FROM join_request WHERE id = ?", String.class,
						requestId);
				if (approveStatus == 200) {
					assertThat(cancelStatus).as("round %d", round).isEqualTo(409);
					assertThat(finalStatus).isEqualTo("APPROVED");
					assertThat(countMembership(account)).as("round %d", round).isEqualTo(1);
				}
				else {
					assertThat(approveStatus).as("round %d", round).isEqualTo(409);
					assertThat(cancelStatus).as("round %d", round).isEqualTo(204);
					assertThat(finalStatus).isEqualTo("CANCELED");
					assertThat(countMembership(account)).as("round %d", round).isZero();
				}
			}
		}

		private List<MockHttpServletResponse> concurrently(int threads, Callable<ResultActions> action) throws Exception {
			List<Callable<ResultActions>> actions = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				actions.add(action);
			}
			return concurrently(actions);
		}

		/** 모든 작업을 한꺼번에 출발시키고, 넣은 순서대로 응답을 돌려준다. */
		private List<MockHttpServletResponse> concurrently(List<Callable<ResultActions>> actions) throws Exception {
			ExecutorService executor = Executors.newFixedThreadPool(actions.size());
			CountDownLatch start = new CountDownLatch(1);
			try {
				List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
				for (Callable<ResultActions> action : actions) {
					futures.add(executor.submit(() -> {
						start.await();
						return action.call().andReturn().getResponse();
					}));
				}
				start.countDown();
				List<MockHttpServletResponse> responses = new ArrayList<>();
				for (Future<MockHttpServletResponse> future : futures) {
					responses.add(future.get());
				}
				return responses;
			}
			finally {
				executor.shutdownNow();
			}
		}

		private List<Integer> statuses(List<MockHttpServletResponse> responses) {
			return responses.stream().map(MockHttpServletResponse::getStatus).toList();
		}

	}

	private long createTeam(long accountId, String name, String description) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("description", description);
		String response = mockMvc
			.perform(post("/api/teams").header("Authorization", bearer(accountId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(response, "$.id")).longValue();
	}

	/** 그 팀 팀장의 토큰으로 초대 코드를 읽는다. */
	private String inviteCodeOf(long team) throws Exception {
		long leaderOfTeam = jdbc.queryForObject("SELECT account_id FROM team_member WHERE team_id = ? AND role = 'LEADER'",
				Long.class, team);
		String response = mockMvc
			.perform(get("/api/teams/{teamId}/invite-code", team).header("Authorization", bearer(leaderOfTeam)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.inviteCode");
	}

	private ResultActions preview(long accountId, String code) throws Exception {
		return mockMvc.perform(get("/api/invites/{code}", code).header("Authorization", bearer(accountId)));
	}

	private ResultActions requestJoin(long accountId, Integer generation) throws Exception {
		return requestJoin(accountId, inviteCode, generation);
	}

	private ResultActions requestJoin(long accountId, String code, Integer generation) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("generation", generation);
		return mockMvc.perform(post("/api/invites/{code}/join-requests", code).header("Authorization", bearer(accountId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body)));
	}

	private long requestJoinId(long accountId, Integer generation) throws Exception {
		return requestJoinId(accountId, inviteCode, generation);
	}

	private long requestJoinId(long accountId, String code, Integer generation) throws Exception {
		String response = requestJoin(accountId, code, generation).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(response, "$.requestId")).longValue();
	}

	private ResultActions pendingList(long accountId) throws Exception {
		return mockMvc.perform(get("/api/teams/{teamId}/join-requests", teamId).header("Authorization", bearer(accountId)));
	}

	private ResultActions approve(long accountId, long team, long requestId) throws Exception {
		return mockMvc.perform(post("/api/teams/{teamId}/join-requests/{requestId}/approve", team, requestId)
			.header("Authorization", bearer(accountId)));
	}

	private ResultActions reject(long accountId, long team, long requestId) throws Exception {
		return mockMvc.perform(post("/api/teams/{teamId}/join-requests/{requestId}/reject", team, requestId)
			.header("Authorization", bearer(accountId)));
	}

	private ResultActions cancel(long accountId, long requestId) throws Exception {
		return mockMvc
			.perform(delete("/api/join-requests/{requestId}", requestId).header("Authorization", bearer(accountId)));
	}

	private ResultActions teamDetail(long accountId) throws Exception {
		return mockMvc.perform(get("/api/teams/{teamId}", teamId).header("Authorization", bearer(accountId)));
	}

	private String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	private int countMembers(long team) {
		return jdbc.queryForObject("SELECT count(*) FROM team_member WHERE team_id = ?", Integer.class, team);
	}

	private int countMembership(long accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM team_member WHERE team_id = ? AND account_id = ?", Integer.class,
				teamId, accountId);
	}

	private int countPending(long accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM join_request WHERE account_id = ? AND status = 'PENDING'",
				Integer.class, accountId);
	}

}
