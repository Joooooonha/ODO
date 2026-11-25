package com.odo.schedule.presentation;

import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.MockMvc;

import static com.odo.schedule.presentation.ScheduleRequests.roomBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연습실 만들기, 목록, 상세, 수정, 삭제. 03_Schedule_spec.md의 "연습실 (통합)"과 API 명세의 연습실 부분.
 * 팀원은 DB에 바로 넣고(TestTeams), 팀과 연습실은 API로 만든다.
 */
@IntegrationTest
class PracticeRoomApiIntegrationTest {

	private static final int CONCURRENT_ROUNDS = 10;

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

	/** 팀원(팀장 아님). */
	private long member;

	/** 어느 팀에도 없다. */
	private long outsider;

	private long teamId;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		api = new ScheduleRequests(mockMvc, accessTokenIssuer, jdbc);
		leader = TestAccounts.create(jdbc, "박준하", clock.instant());
		member = TestAccounts.create(jdbc, "김지수", clock.instant());
		outsider = TestAccounts.create(jdbc, "외부인", clock.instant());
		teamId = api.createTeam(leader, "블랙홀");
		TestTeams.addMember(jdbc, teamId, member, 25, clock.instant());
	}

	private static Map<String, Object> fullBody() {
		Map<String, Object> body = roomBody("합정 A룸");
		body.put("address", "서울 마포구 양화로 1");
		body.put("password", "1234*");
		body.put("rentalFee", 15000);
		body.put("memo", "정수기 있음\n화장실은 2층");
		return body;
	}

	private int countRooms() {
		return jdbc.queryForObject("SELECT count(*) FROM practice_room WHERE team_id = ?", Integer.class, teamId);
	}

	@Nested
	@DisplayName("만들기")
	class Create {

		@Test
		@DisplayName("팀장은 이름만으로 만들 수 있고, 201과 Location, 연습실 상세를 받는다")
		void nameOnly() throws Exception {
			api.createRoom(leader, teamId, roomBody("  합정 A룸  "))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern("/api/practice-rooms/\\d+")))
				.andExpect(jsonPath("$.teamId").value(teamId))
				.andExpect(jsonPath("$.name").value("합정 A룸"))
				.andExpect(jsonPath("$.address").value(nullValue()))
				.andExpect(jsonPath("$.password").value(nullValue()))
				.andExpect(jsonPath("$.rentalFee").value(nullValue()))
				.andExpect(jsonPath("$.memo").value(nullValue()));
		}

		@Test
		@DisplayName("모든 항목을 넣으면 상세에 그대로 보인다. 다른 언어의 글자도 쓸 수 있다")
		void allFields() throws Exception {
			Map<String, Object> body = fullBody();
			body.put("name", "スタジオ 3");

			long roomId = api.createRoomId(leader, teamId, body);

			api.room(member, roomId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("スタジオ 3"))
				.andExpect(jsonPath("$.address").value("서울 마포구 양화로 1"))
				.andExpect(jsonPath("$.password").value("1234*"))
				.andExpect(jsonPath("$.rentalFee").value(15000))
				.andExpect(jsonPath("$.memo").value("정수기 있음\n화장실은 2층"));
		}

		@Test
		@DisplayName("비밀번호는 DB에 암호문으로 저장된다")
		void passwordEncrypted() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());

			String stored = jdbc.queryForObject("SELECT password_encrypted FROM practice_room WHERE id = ?", String.class,
					roomId);
			assertThat(stored).startsWith("v1:").doesNotContain("1234");
		}

		@Test
		@DisplayName("대관비는 0과 2,147,483,647을 받는다")
		void rentalFeeBounds() throws Exception {
			Map<String, Object> body = roomBody("무료 연습실");
			body.put("rentalFee", 0);
			api.createRoom(leader, teamId, body).andExpect(status().isCreated()).andExpect(jsonPath("$.rentalFee").value(0));

			body.put("rentalFee", 2_147_483_647L);
			api.createRoom(leader, teamId, body)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rentalFee").value(2_147_483_647L));
		}

		@Test
		@DisplayName("팀장이 아닌 팀원은 403 NOT_TEAM_LEADER, 팀원이 아니면 404 TEAM_NOT_FOUND이고 만들어지지 않는다")
		void permission() throws Exception {
			api.createRoom(member, teamId, roomBody("합정 A룸"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.createRoom(outsider, teamId, roomBody("합정 A룸"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));

			assertThat(countRooms()).isZero();
		}

		@Test
		@DisplayName("항목이 규칙에 맞지 않으면 그 항목의 400이고 만들어지지 않는다")
		void invalidFields() throws Exception {
			assertInvalid("name", "합정 🎵", "INVALID_PRACTICE_ROOM_NAME");
			assertInvalid("name", " ", "INVALID_PRACTICE_ROOM_NAME");
			assertInvalid("name", "가".repeat(51), "INVALID_PRACTICE_ROOM_NAME");
			assertInvalid("address", "가".repeat(51), "INVALID_PRACTICE_ROOM_ADDRESS");
			assertInvalid("password", "1".repeat(51), "INVALID_PRACTICE_ROOM_PASSWORD");
			assertInvalid("memo", "가".repeat(301), "INVALID_PRACTICE_ROOM_MEMO");
			assertInvalid("rentalFee", -1, "INVALID_RENTAL_FEE");
			assertInvalid("rentalFee", 2_147_483_648L, "INVALID_RENTAL_FEE");

			assertThat(countRooms()).isZero();
		}

		@Test
		@DisplayName("이름이 없으면 400 INVALID_PRACTICE_ROOM_NAME이다")
		void nameMissing() throws Exception {
			Map<String, Object> body = fullBody();
			body.remove("name");

			api.createRoom(leader, teamId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_ROOM_NAME"));
		}

		private void assertInvalid(String field, Object value, String code) throws Exception {
			Map<String, Object> body = fullBody();
			body.put(field, value);
			api.createRoom(leader, teamId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(code));
		}

	}

	@Nested
	@DisplayName("목록과 상세")
	class Read {

		@Test
		@DisplayName("목록은 삭제하지 않은 연습실을 이름순으로 보여 주고, 비밀번호와 메모는 넣지 않는다")
		void list() throws Exception {
			api.createRoomId(leader, teamId, "홍대 B룸");
			api.createRoomId(leader, teamId, fullBody());
			long deleted = api.createRoomId(leader, teamId, "강남 C룸");
			api.deleteRoom(leader, deleted).andExpect(status().isNoContent());

			api.listRooms(member, teamId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].name").value("합정 A룸"))
				.andExpect(jsonPath("$[0].address").value("서울 마포구 양화로 1"))
				.andExpect(jsonPath("$[0].rentalFee").value(15000))
				.andExpect(jsonPath("$[0]", not(hasKey("password"))))
				.andExpect(jsonPath("$[0]", not(hasKey("memo"))))
				.andExpect(jsonPath("$[1].name").value("홍대 B룸"));
		}

		@Test
		@DisplayName("팀원이 아니면 목록은 404 TEAM_NOT_FOUND, 상세는 404 PRACTICE_ROOM_NOT_FOUND다")
		void outsider() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());

			api.listRooms(outsider, teamId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
			api.room(outsider, roomId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_ROOM_NOT_FOUND"));
		}

		@Test
		@DisplayName("없는 연습실과 삭제한 연습실의 상세는 404 PRACTICE_ROOM_NOT_FOUND다")
		void missingOrDeleted() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());
			api.deleteRoom(leader, roomId).andExpect(status().isNoContent());

			api.room(member, roomId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_ROOM_NOT_FOUND"));
			api.room(member, 999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_ROOM_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("수정")
	class Update {

		@Test
		@DisplayName("팀장은 보낸 값으로 모두 바꾼다. 보내지 않은 선택 항목은 비워진다")
		void replaceAll() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());
			Map<String, Object> body = roomBody("합정 A룸(리모델링)");
			body.put("rentalFee", 20000);

			api.updateRoom(leader, roomId, body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("합정 A룸(리모델링)"))
				.andExpect(jsonPath("$.rentalFee").value(20000))
				.andExpect(jsonPath("$.address").value(nullValue()))
				.andExpect(jsonPath("$.password").value(nullValue()))
				.andExpect(jsonPath("$.memo").value(nullValue()));
		}

		@Test
		@DisplayName("팀장이 아니면 403, 팀원이 아니거나 삭제한 연습실이면 404 PRACTICE_ROOM_NOT_FOUND다")
		void permissionAndMissing() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());

			api.updateRoom(member, roomId, roomBody("바꾼 이름"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));
			api.updateRoom(outsider, roomId, roomBody("바꾼 이름"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_ROOM_NOT_FOUND"));

			api.deleteRoom(leader, roomId).andExpect(status().isNoContent());
			api.updateRoom(leader, roomId, roomBody("바꾼 이름"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_ROOM_NOT_FOUND"));
		}

		@Test
		@DisplayName("항목이 규칙에 맞지 않으면 만들기와 같은 400이고 바뀌지 않는다")
		void invalid() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());

			api.updateRoom(leader, roomId, roomBody("합정 🎵"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PRACTICE_ROOM_NAME"));

			api.room(leader, roomId).andExpect(jsonPath("$.name").value("합정 A룸"));
		}

	}

	@Nested
	@DisplayName("삭제")
	class Delete {

		@Test
		@DisplayName("팀장이 삭제하면 204이고, 다시 삭제하면 404다. 행은 DB에 남는다")
		void delete() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());

			api.deleteRoom(leader, roomId).andExpect(status().isNoContent());
			api.deleteRoom(leader, roomId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_ROOM_NOT_FOUND"));

			assertThat(countRooms()).isEqualTo(1);
		}

		@Test
		@DisplayName("팀장이 아닌 팀원은 403이고 삭제되지 않는다")
		void notLeader() throws Exception {
			long roomId = api.createRoomId(leader, teamId, fullBody());

			api.deleteRoom(member, roomId)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_TEAM_LEADER"));

			api.room(member, roomId).andExpect(status().isOk());
		}

		@Test
		@DisplayName("같은 연습실을 동시에 두 번 삭제해도 하나만 204이고 나머지는 404다")
		void concurrentDelete() throws Exception {
			for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
				long roomId = api.createRoomId(leader, teamId, "동시 삭제 " + round);

				List<Integer> statuses = Concurrently.statuses(Concurrently.run(2, () -> api.deleteRoom(leader, roomId)));

				assertThat(statuses).containsExactlyInAnyOrder(204, 404);
			}
		}

	}

}
