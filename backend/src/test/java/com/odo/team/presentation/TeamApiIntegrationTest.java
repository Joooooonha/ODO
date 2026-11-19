package com.odo.team.presentation;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 팀 스페이스 만들기, 내 팀 목록, 팀 상세, 팀원 목록, 기수 변경, 초대 코드, 팀장 권한. 02_TeamSpace_spec.md의 "팀 스페이스 (통합)".
 * 팀원은 입장 요청을 거치지 않고 DB에 바로 넣는다(TestTeams). 입장 흐름은 JoinRequestIntegrationTest가 다룬다.
 */
@IntegrationTest
class TeamApiIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private long leader;

	private long member;

	private long outsider;

	@BeforeEach
	void setUp() {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		member = TestAccounts.create(jdbc, "김지수", clock.instant());
		outsider = TestAccounts.create(jdbc, "이민호", clock.instant());
	}

	@Nested
	@DisplayName("팀 스페이스 만들기")
	class Create {

		@Test
		@DisplayName("만들면 201과 팀 상세를 받고, 만든 사람이 기수와 함께 팀장으로 들어간다")
		void create() throws Exception {
			createTeam(leader, Map.of("name", "블랙홀", "description", "매주 화목 연습하는 힙합 팀입니다", "generation", 24))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.name").value("블랙홀"))
				.andExpect(jsonPath("$.description").value("매주 화목 연습하는 힙합 팀입니다"))
				.andExpect(jsonPath("$.leaderName").value("박준하"))
				.andExpect(jsonPath("$.myRole").value("LEADER"));

			long teamId = onlyTeamId();
			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(leader)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].accountId").value(leader))
				.andExpect(jsonPath("$[0].name").value("박준하"))
				.andExpect(jsonPath("$[0].generation").value(24))
				.andExpect(jsonPath("$[0].role").value("LEADER"));
		}

		@Test
		@DisplayName("소개 문구와 기수 없이 만들 수 있다")
		void withoutOptional() throws Exception {
			createTeam(leader, Map.of("name", "블랙홀")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.description").value(nullValue()));

			mockMvc.perform(get("/api/teams/{teamId}/members", onlyTeamId()).header("Authorization", bearer(leader)))
				.andExpect(jsonPath("$[0].generation").value(nullValue()));
		}

		@Test
		@DisplayName("팀 이름은 앞뒤 공백을 빼고 저장하고, 빈 소개 문구는 소개 없음으로 저장한다")
		void normalizes() throws Exception {
			createTeam(leader, Map.of("name", "  블랙홀  ", "description", "")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("블랙홀"))
				.andExpect(jsonPath("$.description").value(nullValue()));
		}

		@ParameterizedTest
		@ValueSource(strings = { "", "   ", "블랙홀!", "🐥팀", "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다" })
		@DisplayName("팀 이름 규칙에 맞지 않으면 400 INVALID_TEAM_NAME이고 팀이 만들어지지 않는다")
		void invalidName(String name) throws Exception {
			createTeam(leader, Map.of("name", name)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_TEAM_NAME"));

			assertThat(countTeams()).isZero();
		}

		@Test
		@DisplayName("팀 이름이 없으면 400 INVALID_TEAM_NAME이다")
		void missingName() throws Exception {
			createTeam(leader, Map.of("description", "소개")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_TEAM_NAME"));
		}

		@Test
		@DisplayName("소개 문구가 100자를 넘으면 400 INVALID_TEAM_DESCRIPTION이고 팀이 만들어지지 않는다")
		void invalidDescription() throws Exception {
			createTeam(leader, Map.of("name", "블랙홀", "description", "가".repeat(101))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_TEAM_DESCRIPTION"));

			assertThat(countTeams()).isZero();
		}

		@ParameterizedTest
		@ValueSource(ints = { 0, 1000 })
		@DisplayName("기수가 1~999가 아니면 400 INVALID_GENERATION이고 팀이 만들어지지 않는다")
		void invalidGeneration(int generation) throws Exception {
			createTeam(leader, Map.of("name", "블랙홀", "generation", generation)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_GENERATION"));

			assertThat(countTeams()).isZero();
		}

		@Test
		@DisplayName("다른 팀과 같은 이름으로 만들 수 있다")
		void duplicateName() throws Exception {
			createTeam(leader, Map.of("name", "블랙홀")).andExpect(status().isCreated());
			createTeam(member, Map.of("name", "블랙홀")).andExpect(status().isCreated());

			assertThat(countTeams()).isEqualTo(2);
		}

		@Test
		@DisplayName("로그인하지 않으면 401이다")
		void unauthenticated() throws Exception {
			mockMvc.perform(post("/api/teams").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"블랙홀\"}"))
				.andExpect(status().isUnauthorized());
		}

	}

	@Nested
	@DisplayName("읽을 수 없는 요청")
	class InvalidRequest {

		@Test
		@DisplayName("경로 변수의 팀 ID가 숫자가 아니면 400 INVALID_REQUEST다")
		void pathVariableTypeMismatch() throws Exception {
			mockMvc.perform(get("/api/teams/{teamId}", "abc").header("Authorization", bearer(leader)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("본문 없이 팀을 만들면 400 INVALID_REQUEST이고 팀이 만들어지지 않는다")
		void missingBody() throws Exception {
			mockMvc.perform(post("/api/teams").header("Authorization", bearer(leader)).contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			assertThat(countTeams()).isZero();
		}

		@Test
		@DisplayName("기수에 숫자가 아닌 값을 넣으면 400 INVALID_REQUEST이고 팀이 만들어지지 않는다")
		void generationTypeMismatch() throws Exception {
			createTeam(leader, Map.of("name", "블랙홀", "generation", "abc")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			assertThat(countTeams()).isZero();
		}

	}

	@Nested
	@DisplayName("내 팀 목록")
	class MyTeams {

		@Test
		@DisplayName("내가 팀원인 팀만 들어온 순서로 역할과 함께 보인다")
		void myTeams() throws Exception {
			long first = createTeamId(leader, "블랙홀");
			clock.advance(Duration.ofMinutes(1));
			long other = createTeamId(member, "화이트홀");
			clock.advance(Duration.ofMinutes(1));
			TestTeams.addMember(jdbc, other, leader, null, clock.instant());
			createTeamId(outsider, "웜홀");

			mockMvc.perform(get("/api/teams").header("Authorization", bearer(leader)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].id").value(first))
				.andExpect(jsonPath("$[0].name").value("블랙홀"))
				.andExpect(jsonPath("$[0].myRole").value("LEADER"))
				.andExpect(jsonPath("$[1].id").value(other))
				.andExpect(jsonPath("$[1].name").value("화이트홀"))
				.andExpect(jsonPath("$[1].myRole").value("MEMBER"));
		}

		@Test
		@DisplayName("속한 팀이 없으면 빈 목록이다")
		void empty() throws Exception {
			mockMvc.perform(get("/api/teams").header("Authorization", bearer(outsider)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
		}

	}

	@Nested
	@DisplayName("팀 상세와 팀원 목록")
	class DetailAndMembers {

		private long teamId;

		@BeforeEach
		void createTeam() throws Exception {
			teamId = createTeamId(leader, "블랙홀");
		}

		@Test
		@DisplayName("팀원은 팀 상세를 본다. myRole은 보는 사람의 역할이다")
		void detail() throws Exception {
			TestTeams.addMember(jdbc, teamId, member, 25, clock.instant());

			mockMvc.perform(get("/api/teams/{teamId}", teamId).header("Authorization", bearer(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(teamId))
				.andExpect(jsonPath("$.name").value("블랙홀"))
				.andExpect(jsonPath("$.leaderName").value("박준하"))
				.andExpect(jsonPath("$.myRole").value("MEMBER"));
		}

		@Test
		@DisplayName("팀원 목록은 팀장이 맨 앞이고 나머지는 들어온 순서다")
		void membersOrder() throws Exception {
			clock.advance(Duration.ofMinutes(1));
			TestTeams.addMember(jdbc, teamId, outsider, 26, clock.instant());
			clock.advance(Duration.ofMinutes(1));
			TestTeams.addMember(jdbc, teamId, member, 25, clock.instant());

			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(member)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].accountId").value(leader))
				.andExpect(jsonPath("$[0].role").value("LEADER"))
				.andExpect(jsonPath("$[1].accountId").value(outsider))
				.andExpect(jsonPath("$[1].name").value("이민호"))
				.andExpect(jsonPath("$[1].generation").value(26))
				.andExpect(jsonPath("$[1].role").value("MEMBER"))
				.andExpect(jsonPath("$[2].accountId").value(member));
		}

		@Test
		@DisplayName("팀원이 아닌 사람이 팀 상세나 팀원 목록을 보면 404 TEAM_NOT_FOUND다")
		void outsider() throws Exception {
			mockMvc.perform(get("/api/teams/{teamId}", teamId).header("Authorization", bearer(outsider)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(outsider)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
		}

		@Test
		@DisplayName("없는 팀 ID는 팀원이 아닌 팀과 같은 404 TEAM_NOT_FOUND다")
		void unknownTeam() throws Exception {
			mockMvc.perform(get("/api/teams/{teamId}", teamId + 1000).header("Authorization", bearer(leader)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("내 기수 바꾸기")
	class ChangeGeneration {

		private long teamId;

		@BeforeEach
		void createTeam() throws Exception {
			teamId = createTeamId(leader, "블랙홀");
			TestTeams.addMember(jdbc, teamId, member, 25, clock.instant());
		}

		@Test
		@DisplayName("팀원은 자기 기수를 바꾸고, 팀원 목록에 바뀐 기수가 보인다")
		void change() throws Exception {
			changeGeneration(member, 26).andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(member))
				.andExpect(jsonPath("$.name").value("김지수"))
				.andExpect(jsonPath("$.generation").value(26))
				.andExpect(jsonPath("$.role").value("MEMBER"));

			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(leader)))
				.andExpect(jsonPath("$[1].generation").value(26));
		}

		@Test
		@DisplayName("null을 보내면 기수를 비운다")
		void clear() throws Exception {
			changeGeneration(member, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.generation").value(nullValue()));
		}

		@Test
		@DisplayName("다른 팀원의 기수는 바뀌지 않는다")
		void onlyMine() throws Exception {
			changeGeneration(leader, 30).andExpect(status().isOk());

			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(leader)))
				.andExpect(jsonPath("$[0].generation").value(30))
				.andExpect(jsonPath("$[1].generation").value(25));
		}

		@Test
		@DisplayName("1~999가 아니면 400 INVALID_GENERATION이고 기수가 바뀌지 않는다")
		void invalid() throws Exception {
			changeGeneration(member, 1000).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_GENERATION"));

			mockMvc.perform(get("/api/teams/{teamId}/members", teamId).header("Authorization", bearer(leader)))
				.andExpect(jsonPath("$[1].generation").value(25));
		}

		@Test
		@DisplayName("팀원이 아니면 404 TEAM_NOT_FOUND다")
		void outsider() throws Exception {
			changeGeneration(outsider, 25).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
		}

		private ResultActions changeGeneration(long accountId, Integer generation) throws Exception {
			Map<String, Object> body = new HashMap<>();
			body.put("generation", generation);
			return mockMvc.perform(patch("/api/teams/{teamId}/members/me", teamId).header("Authorization", bearer(accountId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body)));
		}

	}

	@Nested
	@DisplayName("초대 코드")
	class InviteCode {

		private long teamId;

		@BeforeEach
		void createTeam() throws Exception {
			teamId = createTeamId(leader, "블랙홀");
		}

		@Test
		@DisplayName("팀장은 초대 코드를 몇 번이고 같은 값으로 본다")
		void view() throws Exception {
			String first = inviteCode();

			assertThat(first).matches("^[A-Za-z0-9_-]{22}$");
			assertThat(inviteCode()).isEqualTo(first);
		}

		@Test
		@DisplayName("새로 발급하면 다른 코드를 받고, 그 뒤로는 새 코드가 보인다")
		void regenerate() throws Exception {
			String before = inviteCode();

			String body = mockMvc
				.perform(post("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(leader)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			String after = JsonPath.read(body, "$.inviteCode");

			assertThat(after).isNotEqualTo(before).matches("^[A-Za-z0-9_-]{22}$");
			assertThat(inviteCode()).isEqualTo(after);
		}

		private String inviteCode() throws Exception {
			String body = mockMvc
				.perform(get("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(leader)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			return JsonPath.read(body, "$.inviteCode");
		}

	}

	@Nested
	@DisplayName("팀장 권한")
	class LeaderOnly {

		private long teamId;

		@BeforeEach
		void createTeam() throws Exception {
			teamId = createTeamId(leader, "블랙홀");
			TestTeams.addMember(jdbc, teamId, member, 25, clock.instant());
		}

		@Test
		@DisplayName("팀장이 아닌 팀원이 팀장 API를 부르면 403 NOT_TEAM_LEADER다")
		void member() throws Exception {
			mockMvc.perform(get("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(member)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			mockMvc.perform(post("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(member)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			mockMvc.perform(get("/api/teams/{teamId}/join-requests", teamId).header("Authorization", bearer(member)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
		}

		@Test
		@DisplayName("팀원이 아닌 사람이 팀장 API를 부르면 404 TEAM_NOT_FOUND다")
		void outsider() throws Exception {
			mockMvc.perform(get("/api/teams/{teamId}/invite-code", teamId).header("Authorization", bearer(outsider)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
			mockMvc.perform(get("/api/teams/{teamId}/join-requests", teamId).header("Authorization", bearer(outsider)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
		}

	}

	private ResultActions createTeam(long accountId, Map<String, ?> body) throws Exception {
		return mockMvc.perform(post("/api/teams").header("Authorization", bearer(accountId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(JSON.writeValueAsString(body)));
	}

	private long createTeamId(long accountId, String name) throws Exception {
		String body = createTeam(accountId, Map.of("name", name)).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private String bearer(long accountId) {
		return "Bearer " + accessTokenIssuer.issue(accountId);
	}

	private long onlyTeamId() {
		return jdbc.queryForObject("SELECT id FROM team", Long.class);
	}

	private int countTeams() {
		return jdbc.queryForObject("SELECT count(*) FROM team", Integer.class);
	}

}
