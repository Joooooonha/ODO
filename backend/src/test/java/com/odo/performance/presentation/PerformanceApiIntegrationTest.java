package com.odo.performance.presentation;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;

import static com.odo.performance.presentation.PerformanceRequests.director;
import static com.odo.performance.presentation.PerformanceRequests.entry;
import static com.odo.performance.presentation.PerformanceRequests.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공연 만들기, 공연 목록, 공연 상세, 공연 수정, 읽을 수 없는 요청. 06_Performance_spec.md의 "공연 (통합)"과 "요청을 읽을 수 없을 때 (통합)".
 * 팀원은 입장 요청을 거치지 않고 DB에 바로 넣는다(TestTeams). 공연은 API로 만든다.
 */
@IntegrationTest
class PerformanceApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private PerformanceRequests api;

	/** 팀장. 기수 24. */
	private long leader;

	/** 팀원. 기수 25. */
	private long dancer;

	/** 팀원. 기수 25. */
	private long choreographer;

	/** 팀원이지만 공연 명단에는 넣지 않는다. 기수 26. */
	private long bystander;

	/** 어느 팀에도 없다. */
	private long outsider;

	private long teamId;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new PerformanceRequests(mockMvc, accessTokenIssuer);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		dancer = TestAccounts.create(jdbc, "김지수", clock.instant());
		choreographer = TestAccounts.create(jdbc, "이민호", clock.instant());
		bystander = TestAccounts.create(jdbc, "최유나", clock.instant());
		outsider = TestAccounts.create(jdbc, "정하늘", clock.instant());
		teamId = api.createTeam(leader, "블랙홀", 24);
		TestTeams.addMember(jdbc, teamId, dancer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, choreographer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, bystander, 26, clock.instant());
	}

	@Nested
	@DisplayName("공연 만들기")
	class Create {

		@Test
		@DisplayName("팀장이 만들면 201, 새 공연의 Location, 공연 상세를 받고, 요청한 명단과 역할로 만들어진다")
		void create() throws Exception {
			MvcResult result = api
				.createPerformance(leader, teamId,
						body("2026 정기공연", "12월 20일 학생회관", List.of(director(leader), member(dancer))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.name").value("2026 정기공연"))
				.andExpect(jsonPath("$.description").value("12월 20일 학생회관"))
				.andExpect(jsonPath("$.myRole").value("DIRECTOR"))
				.andReturn();
			long performanceId = idOf(result);

			assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/performances/" + performanceId);
			api.listMembers(leader, performanceId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].accountId").value(leader))
				.andExpect(jsonPath("$[0].role").value("DIRECTOR"))
				.andExpect(jsonPath("$[1].accountId").value(dancer))
				.andExpect(jsonPath("$[1].role").value("MEMBER"));
			api.listPerformances(dancer, teamId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(performanceId))
				.andExpect(jsonPath("$[0].myRole").value("MEMBER"));
		}

		@Test
		@DisplayName("팀장이 디렉터가 아닌 공연(팀장의 역할이 MEMBER)도 만들 수 있다")
		void leaderAsMember() throws Exception {
			api.createPerformance(leader, teamId, body("2026 정기공연", null, List.of(member(leader), director(dancer))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.myRole").value("MEMBER"));
		}

		@Test
		@DisplayName("팀장 혼자인 공연도 만들 수 있다")
		void leaderAlone() throws Exception {
			long performanceId = idOf(api.createPerformance(leader, teamId, body("2026 정기공연", null, List.of(director(leader))))
				.andExpect(status().isCreated())
				.andReturn());

			api.listMembers(leader, performanceId).andExpect(jsonPath("$", hasSize(1)));
		}

		@Test
		@DisplayName("이름은 앞뒤 공백을 빼고 저장하고, 설명이 없거나 빈 문자열이면 설명 없음으로 저장한다")
		void normalizes() throws Exception {
			api.createPerformance(leader, teamId, body("  2026 정기공연  ", "", List.of(director(leader))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("2026 정기공연"))
				.andExpect(jsonPath("$.description").value(nullValue()));
			api.createPerformance(leader, teamId, Map.of("name", "겨울 공연", "members", List.of(director(leader))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.description").value(nullValue()));
		}

		@Test
		@DisplayName("팀장이 아닌 팀원은 403 NOT_TEAM_LEADER이고 공연이 만들어지지 않는다")
		void notLeader() throws Exception {
			api.createPerformance(dancer, teamId, body("2026 정기공연", null, List.of(director(leader), director(dancer))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("팀원이 아닌 사람은 404 TEAM_NOT_FOUND이고, 없는 팀도 같다")
		void outsider() throws Exception {
			api.createPerformance(outsider, teamId, body("2026 정기공연", null, List.of(director(outsider))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
			api.createPerformance(leader, teamId + 1000, body("2026 정기공연", null, List.of(director(leader))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("명단에 팀장이 없으면(명단이 없거나 빈 경우 포함) 400 LEADER_NOT_IN_MEMBERS이고 공연이 만들어지지 않는다")
		void leaderNotInMembers() throws Exception {
			api.createPerformance(leader, teamId, body("2026 정기공연", null, List.of(director(dancer))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LEADER_NOT_IN_MEMBERS"));
			api.createPerformance(leader, teamId, body("2026 정기공연", null, List.of()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LEADER_NOT_IN_MEMBERS"));
			api.createPerformance(leader, teamId, Map.of("name", "2026 정기공연"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LEADER_NOT_IN_MEMBERS"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("같은 사람이 역할을 달리해 두 번 있어도 400 DUPLICATE_MEMBER이고 공연이 만들어지지 않는다")
		void duplicateMember() throws Exception {
			api.createPerformance(leader, teamId,
					body("2026 정기공연", null, List.of(director(leader), member(dancer), director(dancer))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DUPLICATE_MEMBER"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("다른 팀의 팀원이나 팀원이 아닌 계정, 없는 계정이 있으면 400 NOT_TEAM_MEMBER이고 공연이 만들어지지 않는다")
		void notTeamMember() throws Exception {
			long otherLeader = TestAccounts.create(jdbc, "한별", clock.instant());
			api.createTeam(otherLeader, "화이트홀", null);

			for (long stranger : List.of(otherLeader, outsider, 999_999L)) {
				api.createPerformance(leader, teamId,
						body("2026 정기공연", null, List.of(director(leader), member(stranger))))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
			}

			assertThat(countPerformances()).isZero();
		}

		@ParameterizedTest
		@ValueSource(strings = { "", "   ", "정기공연!", "🎵 정기공연", "ㅋㅋ공연" })
		@DisplayName("공연 이름 규칙에 맞지 않으면 400 INVALID_PERFORMANCE_NAME이고 공연이 만들어지지 않는다")
		void invalidName(String name) throws Exception {
			api.createPerformance(leader, teamId, body(name, null, List.of(director(leader))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PERFORMANCE_NAME"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("공연 이름이 없거나 101자면 400 INVALID_PERFORMANCE_NAME이다")
		void missingOrLongName() throws Exception {
			api.createPerformance(leader, teamId, Map.of("members", List.of(director(leader))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PERFORMANCE_NAME"));
			api.createPerformance(leader, teamId, body("가".repeat(101), null, List.of(director(leader))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PERFORMANCE_NAME"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("공연 설명이 100자를 넘으면 400 INVALID_PERFORMANCE_DESCRIPTION이고 공연이 만들어지지 않는다")
		void invalidDescription() throws Exception {
			api.createPerformance(leader, teamId, body("2026 정기공연", "가".repeat(101), List.of(director(leader))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PERFORMANCE_DESCRIPTION"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("명단 항목의 accountId나 role이 없거나 role이 DIRECTOR, MEMBER가 아니면 400 INVALID_REQUEST이고 공연이 만들어지지 않는다")
		void invalidEntry() throws Exception {
			List<Map<String, Object>> invalidEntries = List.of(entry(null, "MEMBER"), entry(dancer, null),
					entry(dancer, "LEADER"));
			for (Map<String, Object> invalid : invalidEntries) {
				api.createPerformance(leader, teamId, body("2026 정기공연", null, List.of(director(leader), invalid)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			}

			assertThat(countPerformances()).isZero();
		}

	}

	@Nested
	@DisplayName("공연 목록")
	class ListPerformances {

		private long first;

		private long second;

		private long third;

		@BeforeEach
		void createPerformances() throws Exception {
			first = api.createPerformanceId(leader, teamId, "봄 공연", director(leader), member(dancer));
			clock.advance(Duration.ofMinutes(1));
			second = api.createPerformanceId(leader, teamId, "여름 공연", member(leader), director(choreographer),
					member(dancer));
			clock.advance(Duration.ofMinutes(1));
			third = api.createPerformanceId(leader, teamId, "가을 공연", director(leader));
		}

		@Test
		@DisplayName("팀원에게는 그 팀에서 내가 명단에 있는 공연만, 최근에 만든 것부터 내 역할과 함께 보인다")
		void onlyMine() throws Exception {
			api.listPerformances(dancer, teamId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].id").value(second))
				.andExpect(jsonPath("$[0].name").value("여름 공연"))
				.andExpect(jsonPath("$[0].description").value(nullValue()))
				.andExpect(jsonPath("$[0].myRole").value("MEMBER"))
				.andExpect(jsonPath("$[1].id").value(first));
			api.listPerformances(choreographer, teamId)
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(second))
				.andExpect(jsonPath("$[0].myRole").value("DIRECTOR"));
		}

		@Test
		@DisplayName("팀장에게는 그 팀의 모든 공연이 최근에 만든 것부터 보인다")
		void leaderSeesAll() throws Exception {
			api.listPerformances(leader, teamId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].id").value(third))
				.andExpect(jsonPath("$[1].id").value(second))
				.andExpect(jsonPath("$[1].myRole").value("MEMBER"))
				.andExpect(jsonPath("$[2].id").value(first));
		}

		@Test
		@DisplayName("명단에 하나도 없는 팀원에게는 빈 목록이다")
		void none() throws Exception {
			api.listPerformances(bystander, teamId).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
		}

		@Test
		@DisplayName("다른 팀의 공연은 그 팀의 명단에 있어도 이 팀의 목록에 보이지 않는다")
		void otherTeam() throws Exception {
			long otherLeader = TestAccounts.create(jdbc, "한별", clock.instant());
			long otherTeam = api.createTeam(otherLeader, "화이트홀", null);
			TestTeams.addMember(jdbc, otherTeam, dancer, 3, clock.instant());
			clock.advance(Duration.ofMinutes(1));
			api.createPerformanceId(otherLeader, otherTeam, "화이트홀 공연", director(otherLeader), member(dancer));

			api.listPerformances(dancer, teamId)
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].id").value(second))
				.andExpect(jsonPath("$[1].id").value(first));
		}

		@Test
		@DisplayName("팀원이 아니면 404 TEAM_NOT_FOUND다")
		void outsider() throws Exception {
			api.listPerformances(outsider, teamId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("공연 상세")
	class Detail {

		private long performanceId;

		@BeforeEach
		void createPerformance() throws Exception {
			performanceId = idOf(api
				.createPerformance(leader, teamId,
						body("2026 정기공연", "12월 20일 학생회관", List.of(director(leader), member(dancer))))
				.andExpect(status().isCreated())
				.andReturn());
		}

		@Test
		@DisplayName("명단에 있는 사람은 팀 ID와 내 역할을 포함한 공연 상세를 본다")
		void view() throws Exception {
			api.performanceDetail(dancer, performanceId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(performanceId))
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.name").value("2026 정기공연"))
				.andExpect(jsonPath("$.description").value("12월 20일 학생회관"))
				.andExpect(jsonPath("$.myRole").value("MEMBER"));
		}

		@Test
		@DisplayName("명단에 없는 팀원, 팀원이 아닌 사람, 없는 공연은 모두 404 PERFORMANCE_NOT_FOUND다")
		void hidden() throws Exception {
			api.performanceDetail(bystander, performanceId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
			api.performanceDetail(outsider, performanceId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
			api.performanceDetail(leader, performanceId + 1000)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("공연 수정")
	class Update {

		private long performanceId;

		@BeforeEach
		void createPerformance() throws Exception {
			performanceId = idOf(api
				.createPerformance(leader, teamId,
						body("2026 정기공연", "12월 20일 학생회관", List.of(director(leader), director(choreographer), member(dancer))))
				.andExpect(status().isCreated())
				.andReturn());
		}

		@Test
		@DisplayName("팀장이 이름과 설명을 보내면 200과 바뀐 공연 상세를 받고, 이후 조회에도 바뀐 값이 보인다")
		void update() throws Exception {
			api.updatePerformance(leader, performanceId, body("  2026 겨울 정기공연  ", "12월 20일 학생회관 대강당"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(performanceId))
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.name").value("2026 겨울 정기공연"))
				.andExpect(jsonPath("$.description").value("12월 20일 학생회관 대강당"))
				.andExpect(jsonPath("$.myRole").value("DIRECTOR"));

			api.performanceDetail(dancer, performanceId)
				.andExpect(jsonPath("$.name").value("2026 겨울 정기공연"))
				.andExpect(jsonPath("$.description").value("12월 20일 학생회관 대강당"));
		}

		@Test
		@DisplayName("설명을 빈 문자열로 보내거나 보내지 않으면 설명이 비워진다")
		void clearDescription() throws Exception {
			api.updatePerformance(leader, performanceId, body("2026 정기공연", ""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value(nullValue()));

			api.updatePerformance(leader, performanceId, body("2026 정기공연", "다시 적은 설명"))
				.andExpect(jsonPath("$.description").value("다시 적은 설명"));
			api.updatePerformance(leader, performanceId, Map.of("name", "2026 정기공연"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value(nullValue()));
		}

		@Test
		@DisplayName("팀장이 아닌 명단의 사람은 디렉터라도 403 NOT_TEAM_LEADER이고 공연은 그대로다")
		void notLeader() throws Exception {
			for (long notLeader : List.of(choreographer, dancer)) {
				api.updatePerformance(notLeader, performanceId, body("바꾼 이름", null))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			}

			api.performanceDetail(leader, performanceId).andExpect(jsonPath("$.name").value("2026 정기공연"));
		}

		@Test
		@DisplayName("명단에 없는 팀원과 팀원이 아닌 사람은 404 PERFORMANCE_NOT_FOUND다")
		void notParticipant() throws Exception {
			for (long hidden : List.of(bystander, outsider)) {
				api.updatePerformance(hidden, performanceId, body("바꾼 이름", null))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
			}
		}

		@Test
		@DisplayName("이름이나 설명이 규칙에 맞지 않으면 400이고 공연은 그대로다")
		void invalid() throws Exception {
			api.updatePerformance(leader, performanceId, body("정기공연!", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PERFORMANCE_NAME"));
			api.updatePerformance(leader, performanceId, body("2026 정기공연", "가".repeat(101)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PERFORMANCE_DESCRIPTION"));

			api.performanceDetail(leader, performanceId)
				.andExpect(jsonPath("$.name").value("2026 정기공연"))
				.andExpect(jsonPath("$.description").value("12월 20일 학생회관"));
		}

	}

	@Nested
	@DisplayName("요청을 읽을 수 없을 때")
	class UnreadableRequest {

		@Test
		@DisplayName("경로 변수가 숫자가 아닌 공연 조회는 400 INVALID_REQUEST다")
		void nonNumericPath() throws Exception {
			mockMvc.perform(get("/api/performances/{performanceId}", "abc").header("Authorization", api.bearer(leader)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("본문 없는 공연 만들기는 400 INVALID_REQUEST이고 공연이 만들어지지 않는다")
		void missingBody() throws Exception {
			mockMvc
				.perform(post("/api/teams/{teamId}/performances", teamId).header("Authorization", api.bearer(leader))
					.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			assertThat(countPerformances()).isZero();
		}

		@Test
		@DisplayName("role이 정해진 값이 아닌 명단 추가는 400 INVALID_REQUEST이고 아무도 추가되지 않는다")
		void unknownRole() throws Exception {
			long performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연", director(leader));

			api.addMembers(leader, performanceId, entry(dancer, "LEADER"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			api.listMembers(leader, performanceId).andExpect(jsonPath("$", hasSize(1)));
		}

	}

	private static Map<String, Object> body(String name, String description) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("description", description);
		return body;
	}

	private static Map<String, Object> body(String name, String description, List<Map<String, Object>> members) {
		Map<String, Object> body = body(name, description);
		body.put("members", members);
		return body;
	}

	private static long idOf(MvcResult result) throws Exception {
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private int countPerformances() {
		return jdbc.queryForObject("SELECT count(*) FROM performance", Integer.class);
	}

}
