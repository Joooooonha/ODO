package com.odo.performance.presentation;

import java.time.Duration;
import java.util.List;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.odo.performance.presentation.PerformanceRequests.director;
import static com.odo.performance.presentation.PerformanceRequests.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 곡 목록, 곡 만들기, 곡 조회, 이름 수정, 삭제, 동시 삭제. 06_Performance_spec.md의 "곡 (통합)".
 * 팀원은 입장 요청을 거치지 않고 DB에 바로 넣는다(TestTeams). 공연과 곡은 API로 만든다.
 */
@IntegrationTest
class SongApiIntegrationTest {

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

	/** 팀장, 디렉터. */
	private long leader;

	/** 디렉터. 곡을 주로 만든다. */
	private long choreographer;

	/** 팀원(MEMBER). */
	private long dancer;

	/** 팀원이지만 공연 명단에 없다. */
	private long bystander;

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
		outsider = TestAccounts.create(jdbc, "정하늘", clock.instant());
		teamId = api.createTeam(leader, "블랙홀", 24);
		TestTeams.addMember(jdbc, teamId, choreographer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, dancer, 25, clock.instant());
		TestTeams.addMember(jdbc, teamId, bystander, 26, clock.instant());
		performanceId = api.createPerformanceId(leader, teamId, "2026 정기공연", director(leader), director(choreographer),
				member(dancer));
	}

	@Nested
	@DisplayName("곡 만들기와 목록")
	class Create {

		@Test
		@DisplayName("디렉터가 만들면 201, 새 곡의 Location, 곡을 받고 만든 사람이 담당 디렉터가 된다")
		void create() throws Exception {
			MvcResult result = api.createSong(choreographer, performanceId, "  Hype Boy (Remix)  ")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.performanceId").value(performanceId))
				.andExpect(jsonPath("$.name").value("Hype Boy (Remix)"))
				.andExpect(jsonPath("$.directorAccountId").value(choreographer))
				.andExpect(jsonPath("$.directorName").value("이민호"))
				.andExpect(jsonPath("$.mine").value(true))
				.andReturn();
			long songId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

			String location = result.getResponse().getHeader("Location");
			assertThat(location).isEqualTo("/api/songs/" + songId);
			mockMvc.perform(get(location).header("Authorization", api.bearer(choreographer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(songId));
		}

		@Test
		@DisplayName("곡 목록은 만든 순서로 보이고, mine은 담당 디렉터에게만 true다")
		void list() throws Exception {
			long first = api.createSongId(choreographer, performanceId, "Hype Boy");
			clock.advance(Duration.ofMinutes(1));
			long second = api.createSongId(leader, performanceId, "Ditto 2");

			api.listSongs(choreographer, performanceId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].id").value(first))
				.andExpect(jsonPath("$[0].performanceId").value(performanceId))
				.andExpect(jsonPath("$[0].name").value("Hype Boy"))
				.andExpect(jsonPath("$[0].directorAccountId").value(choreographer))
				.andExpect(jsonPath("$[0].directorName").value("이민호"))
				.andExpect(jsonPath("$[0].mine").value(true))
				.andExpect(jsonPath("$[1].id").value(second))
				.andExpect(jsonPath("$[1].directorName").value("박준하"))
				.andExpect(jsonPath("$[1].mine").value(false));
			api.listSongs(dancer, performanceId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].mine").value(false))
				.andExpect(jsonPath("$[1].mine").value(false));
		}

		@ParameterizedTest
		@ValueSource(strings = { "#1 Song*", "사랑♥", "アイドル", "Love ©" })
		@DisplayName("특수문자, 다른 언어, 이모지 표시가 없는 기호가 든 곡 이름은 만들 수 있다")
		void allowedName(String name) throws Exception {
			api.createSong(choreographer, performanceId, name)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name));
		}

		@ParameterizedTest
		@ValueSource(strings = { "Hype Boy 🎵", "Love ♥️", "Hype\nBoy", "", "   " })
		@DisplayName("이모지나 줄바꿈이 들었거나 비어 있으면 400 INVALID_SONG_NAME이고 곡이 만들어지지 않는다")
		void invalidName(String name) throws Exception {
			api.createSong(choreographer, performanceId, name)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SONG_NAME"));

			assertThat(countSongs()).isZero();
		}

		@Test
		@DisplayName("곡 이름이 없거나 101자면 400 INVALID_SONG_NAME이다")
		void missingOrLongName() throws Exception {
			api.createSong(choreographer, performanceId, null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SONG_NAME"));
			api.createSong(choreographer, performanceId, "가".repeat(101))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SONG_NAME"));

			assertThat(countSongs()).isZero();
		}

		@Test
		@DisplayName("디렉터가 아닌 명단의 팀원은 403 NOT_DIRECTOR이고 곡이 만들어지지 않는다")
		void notDirector() throws Exception {
			api.createSong(dancer, performanceId, "Hype Boy")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_DIRECTOR"));

			assertThat(countSongs()).isZero();
		}

		@Test
		@DisplayName("명단에 없는 사람은 곡 만들기와 곡 목록 모두 404 PERFORMANCE_NOT_FOUND다")
		void notParticipant() throws Exception {
			for (long hidden : List.of(bystander, outsider)) {
				api.createSong(hidden, performanceId, "Hype Boy")
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
				api.listSongs(hidden, performanceId)
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
			}

			assertThat(countSongs()).isZero();
		}

	}

	@Nested
	@DisplayName("곡 조회, 수정, 삭제")
	class Manage {

		private long songId;

		@BeforeEach
		void createSong() throws Exception {
			songId = api.createSongId(choreographer, performanceId, "Hype Boy");
		}

		@Test
		@DisplayName("명단에 있는 사람은 곡을 본다")
		void view() throws Exception {
			api.song(dancer, songId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(songId))
				.andExpect(jsonPath("$.name").value("Hype Boy"))
				.andExpect(jsonPath("$.directorAccountId").value(choreographer))
				.andExpect(jsonPath("$.mine").value(false));
		}

		@Test
		@DisplayName("담당 디렉터는 곡 이름을 고치고 200과 바뀐 곡을 받는다")
		void rename() throws Exception {
			api.renameSong(choreographer, songId, "  Hype Boy (Live ver.)  ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(songId))
				.andExpect(jsonPath("$.name").value("Hype Boy (Live ver.)"))
				.andExpect(jsonPath("$.mine").value(true));

			api.listSongs(dancer, performanceId).andExpect(jsonPath("$[0].name").value("Hype Boy (Live ver.)"));
		}

		@Test
		@DisplayName("이모지가 든 이름으로 고치면 400 INVALID_SONG_NAME이고 이름은 그대로다")
		void renameInvalid() throws Exception {
			api.renameSong(choreographer, songId, "Hype Boy 🎵")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SONG_NAME"));

			api.song(choreographer, songId).andExpect(jsonPath("$.name").value("Hype Boy"));
		}

		@Test
		@DisplayName("담당이 아닌 사람은 팀장인 디렉터라도 수정과 삭제 모두 403 NOT_SONG_DIRECTOR이고 곡은 그대로다")
		void notOwner() throws Exception {
			for (long notOwner : List.of(leader, dancer)) {
				api.renameSong(notOwner, songId, "바꾼 이름")
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("NOT_SONG_DIRECTOR"));
				api.deleteSong(notOwner, songId)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("NOT_SONG_DIRECTOR"));
			}

			api.song(choreographer, songId).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Hype Boy"));
		}

		@Test
		@DisplayName("명단에 없는 사람이 곡 URL을 부르면 조회, 수정, 삭제 모두 404 SONG_NOT_FOUND다")
		void notParticipant() throws Exception {
			for (long hidden : List.of(bystander, outsider)) {
				api.song(hidden, songId)
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
				api.renameSong(hidden, songId, "바꾼 이름")
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
				api.deleteSong(hidden, songId)
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
			}

			api.song(choreographer, songId).andExpect(status().isOk());
		}

		@Test
		@DisplayName("없는 곡은 404 SONG_NOT_FOUND다")
		void missing() throws Exception {
			api.song(choreographer, songId + 1000)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
		}

		@Test
		@DisplayName("담당 디렉터가 삭제하면 204이고, 곡 목록에서 빠지며 조회, 수정, 다시 삭제가 모두 404 SONG_NOT_FOUND다")
		void delete() throws Exception {
			long other = api.createSongId(leader, performanceId, "Ditto");

			api.deleteSong(choreographer, songId).andExpect(status().isNoContent());

			api.listSongs(choreographer, performanceId)
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(other));
			api.song(choreographer, songId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
			api.renameSong(choreographer, songId, "바꾼 이름")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
			api.deleteSong(choreographer, songId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
		}

		@Test
		@DisplayName("삭제한 곡의 행은 DB에 이름과 삭제 시각과 함께 남는다")
		void softDelete() throws Exception {
			api.deleteSong(choreographer, songId).andExpect(status().isNoContent());

			assertThat(jdbc.queryForObject("SELECT name FROM song WHERE id = ?", String.class, songId))
				.isEqualTo("Hype Boy");
			assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM song WHERE id = ?", Boolean.class, songId))
				.isTrue();
		}

		@Test
		@DisplayName("같은 곡을 동시에 두 번 삭제해도 하나만 204이고 나머지는 404 SONG_NOT_FOUND다")
		void concurrentDelete() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long target = api.createSongId(choreographer, performanceId, "동시 삭제 " + round);

				List<MockHttpServletResponse> responses = Concurrently.run(2, () -> api.deleteSong(choreographer, target));

				assertThat(Concurrently.statuses(responses)).as("round %d", round).containsExactlyInAnyOrder(204, 404);
				assertThat(responses.stream().filter(r -> r.getStatus() == 404))
					.allSatisfy(r -> assertThat(JsonPath.<String>read(r.getContentAsString(), "$.code"))
						.isEqualTo("SONG_NOT_FOUND"));
				assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM song WHERE id = ?", Boolean.class,
						target))
					.as("round %d", round)
					.isTrue();
			}
		}

	}

	private int countSongs() {
		return jdbc.queryForObject("SELECT count(*) FROM song", Integer.class);
	}

}
