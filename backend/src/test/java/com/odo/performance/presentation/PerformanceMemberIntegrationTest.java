package com.odo.performance.presentation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static com.odo.performance.presentation.PerformanceRequests.director;
import static com.odo.performance.presentation.PerformanceRequests.entry;
import static com.odo.performance.presentation.PerformanceRequests.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 명단 조회, 명단에 추가, 디렉터 지정, 동시 추가. 06_Performance_spec.md의 "명단 (통합)".
 * 팀원은 입장 요청을 거치지 않고 DB에 바로 넣는다(TestTeams). 공연은 API로 만든다.
 */
@IntegrationTest
class PerformanceMemberIntegrationTest {

	private static final int CONCURRENT_ROUNDS = 10;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	private PerformanceRequests api;

	/** 팀장, 디렉터. 기수 24. */
	private long leader;

	/** 디렉터. 기수 25. */
	private long choreographer;

	/** 팀원(MEMBER). 기수 25. */
	private long dancer;

	/** 팀원이지만 처음에는 명단에 없다. 기수 26. */
	private long bystander;

	/** 팀원이지만 처음에는 명단에 없다. 기수 27. */
	private long newcomer;

	/** 어느 팀에도 없다. */
	private long outsider;

	private long teamId;

	private long performanceId;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new PerformanceRequests(mockMvc, accessTokenIssuer);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		choreographer = TestAccounts.create(jdbc, "이민호", clock.instant());
		dancer = TestAccounts.create(jdbc, "김지수", clock.instant());
		bystander = TestAccounts.create(jdbc, "최유나", clock.instant());
		newcomer = TestAccounts.create(jdbc, "한별", clock.instant());
		outsider = TestAccounts.create(jdbc, "정하늘", clock.instant());
		teamId = api.createTeam(leader, "블랙홀", 24);
		TestTeams.addMember(jdbc, teamId, choreographer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, dancer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, bystander, 26, clock.instant());
		TestTeams.addMember(jdbc, teamId, newcomer, 27, clock.instant());
		// 요청 순서를 역할 순서와 다르게 둬서, 명단이 디렉터 먼저 정렬되는지 본다.
		performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연", member(dancer), director(leader),
				director(choreographer));
		clock.advance(Duration.ofMinutes(1));
	}

	@Nested
	@DisplayName("명단 조회")
	class ListMembers {

		@Test
		@DisplayName("디렉터가 먼저, 같은 역할 안에서는 들어온 순서로 이름, 기수, 역할과 함께 보인다")
		void order() throws Exception {
			api.listMembers(dancer, performanceId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].accountId").value(leader))
				.andExpect(jsonPath("$[0].name").value("박준하"))
				.andExpect(jsonPath("$[0].generation").value(24))
				.andExpect(jsonPath("$[0].role").value("DIRECTOR"))
				.andExpect(jsonPath("$[1].accountId").value(choreographer))
				.andExpect(jsonPath("$[1].role").value("DIRECTOR"))
				.andExpect(jsonPath("$[2].accountId").value(dancer))
				.andExpect(jsonPath("$[2].name").value("김지수"))
				.andExpect(jsonPath("$[2].generation").value(25))
				.andExpect(jsonPath("$[2].role").value("MEMBER"));
		}

		@Test
		@DisplayName("나중에 들어와 디렉터로 지정된 사람은 먼저 있던 디렉터 뒤, 팀원 앞에 보인다")
		void orderAfterDesignation() throws Exception {
			api.addMembers(leader, performanceId, member(bystander)).andExpect(status().isCreated());
			api.changeRole(leader, performanceId, bystander, Map.of("role", "DIRECTOR")).andExpect(status().isOk());

			api.listMembers(leader, performanceId)
				.andExpect(jsonPath("$", hasSize(4)))
				.andExpect(jsonPath("$[0].accountId").value(leader))
				.andExpect(jsonPath("$[1].accountId").value(choreographer))
				.andExpect(jsonPath("$[2].accountId").value(bystander))
				.andExpect(jsonPath("$[3].accountId").value(dancer));
		}

		@Test
		@DisplayName("명단에 없는 팀원과 팀원이 아닌 사람은 404 PERFORMANCE_NOT_FOUND다")
		void hidden() throws Exception {
			for (long hidden : List.of(bystander, outsider)) {
				api.listMembers(hidden, performanceId)
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
			}
		}

	}

	@Nested
	@DisplayName("명단에 추가")
	class Add {

		@Test
		@DisplayName("팀장은 여러 명을 한 번에 추가하고, 201과 추가된 항목을 요청 순서로 받으며 Location은 없다")
		void add() throws Exception {
			api.addMembers(leader, performanceId, member(bystander), director(newcomer))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].accountId").value(bystander))
				.andExpect(jsonPath("$[0].name").value("최유나"))
				.andExpect(jsonPath("$[0].generation").value(26))
				.andExpect(jsonPath("$[0].role").value("MEMBER"))
				.andExpect(jsonPath("$[1].accountId").value(newcomer))
				.andExpect(jsonPath("$[1].role").value("DIRECTOR"));

			assertThat(countRoster()).isEqualTo(5);
		}

		@Test
		@DisplayName("추가된 사람에게 그 공연이 공연 목록에 보이고 상세를 볼 수 있다")
		void visibleAfterAdd() throws Exception {
			api.addMembers(leader, performanceId, director(newcomer)).andExpect(status().isCreated());

			api.listPerformances(newcomer, teamId)
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(performanceId))
				.andExpect(jsonPath("$[0].myRole").value("DIRECTOR"));
			api.performanceDetail(newcomer, performanceId).andExpect(status().isOk());
		}

		@Test
		@DisplayName("이미 명단에 있는 사람이 섞이면 409 ALREADY_IN_PERFORMANCE이고 아무도 추가되지 않는다")
		void alreadyInPerformance() throws Exception {
			api.addMembers(leader, performanceId, member(bystander), member(dancer))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_IN_PERFORMANCE"));

			assertThat(countRoster()).isEqualTo(3);
		}

		@Test
		@DisplayName("명단이 비었거나 없으면 400 EMPTY_MEMBERS다")
		void empty() throws Exception {
			api.addMembersBody(leader, performanceId, Map.of("members", List.of()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMPTY_MEMBERS"));
			api.addMembersBody(leader, performanceId, Map.of())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMPTY_MEMBERS"));
		}

		@Test
		@DisplayName("팀원이 아닌 계정이나 다른 팀의 팀원이 섞이면 400 NOT_TEAM_MEMBER이고 아무도 추가되지 않는다")
		void notTeamMember() throws Exception {
			long otherLeader = TestAccounts.create(jdbc, "윤서진", clock.instant());
			api.createTeam(otherLeader, "화이트홀", null);

			for (long stranger : List.of(outsider, otherLeader)) {
				api.addMembers(leader, performanceId, member(bystander), member(stranger))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("NOT_TEAM_MEMBER"));
			}

			assertThat(countRoster()).isEqualTo(3);
		}

		@Test
		@DisplayName("같은 사람이 두 번 있으면 400 DUPLICATE_MEMBER이고 아무도 추가되지 않는다")
		void duplicate() throws Exception {
			api.addMembers(leader, performanceId, member(bystander), director(bystander))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DUPLICATE_MEMBER"));

			assertThat(countRoster()).isEqualTo(3);
		}

		@Test
		@DisplayName("항목의 accountId나 role이 없으면 400 INVALID_REQUEST이고 아무도 추가되지 않는다")
		void invalidEntry() throws Exception {
			api.addMembers(leader, performanceId, member(bystander), entry(null, "MEMBER"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			api.addMembers(leader, performanceId, member(bystander), entry(newcomer, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

			assertThat(countRoster()).isEqualTo(3);
		}

		@Test
		@DisplayName("팀장이 아닌 명단의 사람은 디렉터라도 403 NOT_TEAM_LEADER이고, 명단에 없는 사람은 404 PERFORMANCE_NOT_FOUND다")
		void notLeader() throws Exception {
			api.addMembers(choreographer, performanceId, member(newcomer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.addMembers(bystander, performanceId, member(newcomer))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));

			assertThat(countRoster()).isEqualTo(3);
		}

		@Test
		@DisplayName("같은 사람을 동시에 두 번 추가해도 명단 행은 하나이고, 나머지 요청은 409 ALREADY_IN_PERFORMANCE다")
		void concurrentAdd() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long account = TestAccounts.create(jdbc, "동시추가", clock.instant());
				TestTeams.addMember(jdbc, teamId, account, 28, clock.instant());

				List<MockHttpServletResponse> responses = Concurrently.run(2,
						() -> api.addMembers(leader, performanceId, member(account)));

				assertThat(Concurrently.statuses(responses)).as("round %d", round).containsExactlyInAnyOrder(201, 409);
				assertThat(responses.stream().filter(r -> r.getStatus() == 409))
					.allSatisfy(r -> assertThat(JsonPath.<String>read(r.getContentAsString(), "$.code"))
						.isEqualTo("ALREADY_IN_PERFORMANCE"));
				assertThat(countRosterRows(account)).as("round %d", round).isEqualTo(1);
			}
		}

	}

	@Nested
	@DisplayName("명단 역할 변경 (디렉터 지정)")
	class ChangeRole {

		@Test
		@DisplayName("팀장은 명단의 팀원을 디렉터로 지정하고 200과 바뀐 명단 항목을 받으며, 지정된 사람은 곡을 만들 수 있다")
		void designateDirector() throws Exception {
			api.createSong(dancer, performanceId, "Ditto").andExpect(status().isForbidden());

			api.changeRole(leader, performanceId, dancer, Map.of("role", "DIRECTOR"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(dancer))
				.andExpect(jsonPath("$.name").value("김지수"))
				.andExpect(jsonPath("$.generation").value(25))
				.andExpect(jsonPath("$.role").value("DIRECTOR"));

			api.performanceDetail(dancer, performanceId).andExpect(jsonPath("$.myRole").value("DIRECTOR"));
			api.createSong(dancer, performanceId, "Ditto").andExpect(status().isCreated());
		}

		@Test
		@DisplayName("이미 디렉터인 사람을 다시 지정해도 그대로 200이다")
		void alreadyDirector() throws Exception {
			api.changeRole(leader, performanceId, choreographer, Map.of("role", "DIRECTOR"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("DIRECTOR"));
			api.changeRole(leader, performanceId, choreographer, Map.of("role", "DIRECTOR"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("DIRECTOR"));
		}

		@Test
		@DisplayName("role이 MEMBER이거나 없으면 400 INVALID_ROLE이고 역할은 그대로다")
		void invalidRole() throws Exception {
			api.changeRole(leader, performanceId, choreographer, Map.of("role", "MEMBER"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ROLE"));
			api.changeRole(leader, performanceId, choreographer, Map.of())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ROLE"));

			api.performanceDetail(choreographer, performanceId).andExpect(jsonPath("$.myRole").value("DIRECTOR"));
		}

		@Test
		@DisplayName("명단에 없는 accountId면 404 PERFORMANCE_MEMBER_NOT_FOUND다")
		void notOnRoster() throws Exception {
			for (long target : List.of(bystander, outsider, 999_999L)) {
				api.changeRole(leader, performanceId, target, Map.of("role", "DIRECTOR"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PERFORMANCE_MEMBER_NOT_FOUND"));
			}
		}

		@Test
		@DisplayName("팀장이 아닌 명단의 사람은 디렉터라도 403 NOT_TEAM_LEADER이고, 명단에 없는 사람은 404 PERFORMANCE_NOT_FOUND다")
		void notLeader() throws Exception {
			api.changeRole(choreographer, performanceId, dancer, Map.of("role", "DIRECTOR"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.changeRole(bystander, performanceId, dancer, Map.of("role", "DIRECTOR"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));

			api.performanceDetail(dancer, performanceId).andExpect(jsonPath("$.myRole").value("MEMBER"));
		}

	}

	private int countRoster() {
		return jdbc.queryForObject("SELECT count(*) FROM performance_member WHERE performance_id = ?", Integer.class,
				performanceId);
	}

	private int countRosterRows(long accountId) {
		return jdbc.queryForObject("SELECT count(*) FROM performance_member WHERE performance_id = ? AND account_id = ?",
				Integer.class, performanceId, accountId);
	}

}
