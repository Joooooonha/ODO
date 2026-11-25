package com.odo.practicevideo.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.odo.auth.application.AccessTokenIssuer;
import com.odo.practicevideo.presentation.PracticeVideoRequests.Fixture;
import com.odo.support.DatabaseCleaner;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.odo.practicevideo.presentation.PracticeVideoRequests.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 목록과 상세. 08_PracticeVideo_spec.md의 "목록과 상세 (통합)", 설계의 "영상 상태", "재생", "목록과 날짜 검색",
 * API 명세의 "영상 목록", "영상 상세". 업로드 시각은 MutableClock으로 정한다.
 */
@IntegrationTest
class PracticeVideoListIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccessTokenIssuer accessTokenIssuer;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	InMemoryObjectStorage storage;

	private PracticeVideoRequests api;

	private Fixture f;

	@BeforeEach
	void setUp() throws Exception {
		DatabaseCleaner.clean(jdbc);
		clock.reset();
		storage.reset();
		api = new PracticeVideoRequests(mockMvc, accessTokenIssuer, jdbc, storage);
		f = api.setUpFixture(clock);
	}

	/** 시계를 at으로 맞추고 choreographer가 READY인 영상을 올린다. */
	private long readyAt(Instant at, String title) throws Exception {
		clock.set(at);
		return api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), title);
	}

	private List<Long> ids(String json) {
		List<Number> ids = JsonPath.read(json, "$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	@Nested
	@DisplayName("목록")
	class ListVideos {

		@Test
		@DisplayName("최근에 올린 것부터 20개와 nextCursor가 오고, 마지막 페이지는 nextCursor가 null이다")
		void pages() throws Exception {
			Instant start = MutableClock.DEFAULT_START;
			List<Long> created = new ArrayList<>();
			for (int i = 0; i < 25; i++) {
				created.add(readyAt(start.plusSeconds(i), "영상 " + i));
			}
			List<Long> newestFirst = created.reversed();

			String first = body(api.list(f.dancer(), f.performanceId()).andExpect(status().isOk()));
			assertThat(ids(first)).containsExactlyElementsOf(newestFirst.subList(0, 20));
			String cursor = JsonPath.read(first, "$.nextCursor");
			assertThat(cursor).isNotBlank();

			String second = body(api.list(f.dancer(), f.performanceId(), Map.of("cursor", cursor))
				.andExpect(status().isOk()));
			assertThat(ids(second)).containsExactlyElementsOf(newestFirst.subList(20, 25));
			assertThat((Object) JsonPath.read(second, "$.nextCursor")).isNull();
		}

		@Test
		@DisplayName("항목에 곡, 제목, 올린 사람, 상태, 업로드 시각, 재생 수, mine이 있다")
		void item() throws Exception {
			Instant at = Instant.parse("2026-11-26T10:00:00Z");
			long id = readyAt(at, "정면");

			api.list(f.choreographer(), f.performanceId())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(id))
				.andExpect(jsonPath("$.items[0].songId").value(f.choreoSong()))
				.andExpect(jsonPath("$.items[0].songName").value("Supernova"))
				.andExpect(jsonPath("$.items[0].title").value("정면"))
				.andExpect(jsonPath("$.items[0].uploaderAccountId").value(f.choreographer()))
				.andExpect(jsonPath("$.items[0].uploaderName").value("이민호"))
				.andExpect(jsonPath("$.items[0].status").value("READY"))
				.andExpect(jsonPath("$.items[0].uploadedAt").value("2026-11-26T10:00:00Z"))
				.andExpect(jsonPath("$.items[0].playCount").value(0))
				.andExpect(jsonPath("$.items[0].mine").value(true));
			api.list(f.dancer(), f.performanceId()).andExpect(jsonPath("$.items[0].mine").value(false));
		}

		@Test
		@DisplayName("첫 페이지를 받은 뒤 새 영상이 올라와도 다음 페이지에 중복이나 누락이 없다")
		void newVideoBetweenPages() throws Exception {
			Instant start = MutableClock.DEFAULT_START;
			List<Long> created = new ArrayList<>();
			for (int i = 0; i < 22; i++) {
				created.add(readyAt(start.plusSeconds(i), "영상 " + i));
			}
			String first = body(api.list(f.dancer(), f.performanceId()));
			String cursor = JsonPath.read(first, "$.nextCursor");

			readyAt(start.plusSeconds(100), "새 영상");

			String second = body(api.list(f.dancer(), f.performanceId(), Map.of("cursor", cursor)));
			assertThat(ids(second)).containsExactly(created.get(1), created.get(0));
		}

		@Test
		@DisplayName("업로드 시각이 같으면 ID가 큰 것이 먼저다. 페이지 경계에서도 겹치거나 빠지지 않는다")
		void sameTime() throws Exception {
			Instant at = Instant.parse("2026-11-26T10:00:00Z");
			List<Long> created = new ArrayList<>();
			for (int i = 0; i < 21; i++) {
				created.add(readyAt(at, "같은 시각 " + i));
			}
			List<Long> idDesc = created.stream().sorted((a, b) -> Long.compare(b, a)).toList();

			String first = body(api.list(f.dancer(), f.performanceId()));
			String second = body(api.list(f.dancer(), f.performanceId(),
					Map.of("cursor", JsonPath.read(first, "$.nextCursor"))));

			assertThat(ids(first)).containsExactlyElementsOf(idDesc.subList(0, 20));
			assertThat(ids(second)).containsExactly(idDesc.get(20));
		}

		@Test
		@DisplayName("업로드 중인 영상은 올린 디렉터의 목록에만 보이고, 완료 기한이 지나면 사라진다")
		void uploading() throws Exception {
			long ready = readyAt(MutableClock.DEFAULT_START, "완료");
			long uploading = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");

			assertThat(ids(body(api.list(f.choreographer(), f.performanceId())))).containsExactly(uploading, ready);
			api.list(f.choreographer(), f.performanceId()).andExpect(jsonPath("$.items[0].status").value("UPLOADING"));
			assertThat(ids(body(api.list(f.dancer(), f.performanceId())))).containsExactly(ready);
			assertThat(ids(body(api.list(f.leader(), f.performanceId())))).containsExactly(ready);

			clock.advance(Duration.ofHours(6).plusSeconds(1));
			assertThat(ids(body(api.list(f.choreographer(), f.performanceId())))).containsExactly(ready);
		}

		@Test
		@DisplayName("삭제 중(DELETING)인 영상은 아무에게도 보이지 않는다")
		void deleting() throws Exception {
			long kept = readyAt(MutableClock.DEFAULT_START, "남김");
			long deleted = readyAt(MutableClock.DEFAULT_START.plusSeconds(1), "지움");
			storage.failAllDeletes(true);
			api.delete(f.choreographer(), deleted).andExpect(status().isNoContent());
			assertThat(api.statusOf(deleted)).isEqualTo("DELETING");

			assertThat(ids(body(api.list(f.choreographer(), f.performanceId())))).containsExactly(kept);
			assertThat(ids(body(api.list(f.dancer(), f.performanceId())))).containsExactly(kept);
		}

		@Test
		@DisplayName("날짜 검색은 한국 시간 업로드일 기준이다. 25일 23:59:59는 25일에, 26일 00:00:00은 26일에 나온다")
		void dateRange() throws Exception {
			long before = readyAt(Instant.parse("2026-11-24T14:59:59Z"), "24일 23:59:59");
			long lastOf25 = readyAt(Instant.parse("2026-11-25T14:59:59Z"), "25일 23:59:59");
			long firstOf26 = readyAt(Instant.parse("2026-11-25T15:00:00Z"), "26일 00:00:00");
			long after = readyAt(Instant.parse("2026-11-26T15:00:00Z"), "27일 00:00:00");

			assertThat(ids(body(api.list(f.dancer(), f.performanceId(), Map.of("from", "2026-11-25", "to", "2026-11-25")))))
				.containsExactly(lastOf25);
			assertThat(ids(body(api.list(f.dancer(), f.performanceId(), Map.of("from", "2026-11-26", "to", "2026-11-26")))))
				.containsExactly(firstOf26);
			assertThat(ids(body(api.list(f.dancer(), f.performanceId(), Map.of("from", "2026-11-25", "to", "2026-11-26")))))
				.containsExactly(firstOf26, lastOf25);
			assertThat(ids(body(api.list(f.dancer(), f.performanceId(), Map.of("from", "2026-11-26")))))
				.containsExactly(after, firstOf26);
			assertThat(ids(body(api.list(f.dancer(), f.performanceId(), Map.of("to", "2026-11-24")))))
				.containsExactly(before);
		}

		@Test
		@DisplayName("from이 to보다 늦으면 400 INVALID_DATE_RANGE, 틀린 커서는 400 INVALID_CURSOR, 날짜 형식이 틀리면 400 INVALID_REQUEST다")
		void invalidParams() throws Exception {
			api.list(f.dancer(), f.performanceId(), Map.of("from", "2026-11-26", "to", "2026-11-25"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
			api.list(f.dancer(), f.performanceId(), Map.of("cursor", "!!!"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
			api.list(f.dancer(), f.performanceId(), Map.of("from", "2026/11/25"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}

		@Test
		@DisplayName("곡을 삭제해도 그 곡의 영상이 남고 곡 이름이 보인다")
		void deletedSong() throws Exception {
			long id = readyAt(MutableClock.DEFAULT_START, "정면");
			api.deleteSong(f.choreographer(), f.choreoSong());

			api.list(f.dancer(), f.performanceId())
				.andExpect(jsonPath("$.items[0].id").value(id))
				.andExpect(jsonPath("$.items[0].songName").value("Supernova"));
		}

		@Test
		@DisplayName("명단에 없는 팀원은 404 PERFORMANCE_NOT_FOUND다")
		void notInRoster() throws Exception {
			api.list(f.bystander(), f.performanceId())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
		}

	}

	@Nested
	@DisplayName("상세")
	class Detail {

		@Test
		@DisplayName("영상 정보와 재생 주소, 3시간 뒤 만료 시각이 온다")
		void detail() throws Exception {
			Instant at = Instant.parse("2026-11-26T10:00:00Z");
			long id = readyAt(at, "정면");
			Instant now = Instant.parse("2026-11-26T12:00:00Z");
			clock.set(now);

			api.detail(f.dancer(), id)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.performanceId").value(f.performanceId()))
				.andExpect(jsonPath("$.songName").value("Supernova"))
				.andExpect(jsonPath("$.title").value("정면"))
				.andExpect(jsonPath("$.description").value(nullValue()))
				.andExpect(jsonPath("$.uploaderName").value("이민호"))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.uploadedAt").value("2026-11-26T10:00:00Z"))
				.andExpect(jsonPath("$.mine").value(false))
				.andExpect(jsonPath("$.playback.url").value(startsWith(InMemoryObjectStorage.BASE_URL + api.objectKeyOf(id))))
				.andExpect(jsonPath("$.playback.expiresAt").value(now.plus(Duration.ofHours(3)).toString()));
		}

		@Test
		@DisplayName("내가 올리는 중인 영상은 409 PRACTICE_VIDEO_NOT_READY, 남이 올리는 중인 영상은 404다")
		void uploading() throws Exception {
			long id = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");

			api.detail(f.choreographer(), id)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
			api.detail(f.dancer(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

		@Test
		@DisplayName("명단에 없는 팀원과 없는 영상은 404 PRACTICE_VIDEO_NOT_FOUND다")
		void notFound() throws Exception {
			long id = readyAt(MutableClock.DEFAULT_START, "정면");

			api.detail(f.bystander(), id)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
			api.detail(f.dancer(), 999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));
		}

	}

}
