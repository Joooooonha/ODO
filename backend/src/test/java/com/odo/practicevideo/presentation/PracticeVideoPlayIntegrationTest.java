package com.odo.practicevideo.presentation;

import java.util.List;

import com.odo.auth.application.AccessTokenIssuer;
import com.odo.practicevideo.presentation.PracticeVideoRequests.Fixture;
import com.odo.support.Concurrently;
import com.odo.support.DatabaseCleaner;
import com.odo.support.InMemoryObjectStorage;
import com.odo.support.IntegrationTest;
import com.odo.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재생 수. 08_PracticeVideo_spec.md의 "재생 수 (통합)", 설계의 "재생 수", API 명세의 "재생 수".
 */
@IntegrationTest
class PracticeVideoPlayIntegrationTest {

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

	private long ready() throws Exception {
		return api.uploadReady(f.choreographer(), f.performanceId(), f.choreoSong(), "정면");
	}

	@Test
	@DisplayName("부를 때마다 204이고 1씩 늘어난다. 같은 사람이 두 번 불러도, 올린 디렉터가 불러도 센다")
	void counts() throws Exception {
		long id = ready();
		api.detail(f.dancer(), id).andExpect(jsonPath("$.playCount").value(0));

		api.play(f.dancer(), id).andExpect(status().isNoContent());
		api.play(f.dancer(), id).andExpect(status().isNoContent());
		api.play(f.choreographer(), id).andExpect(status().isNoContent());

		api.detail(f.dancer(), id).andExpect(jsonPath("$.playCount").value(3));
		api.list(f.leader(), f.performanceId()).andExpect(jsonPath("$.items[0].playCount").value(3));
	}

	@RepeatedTest(10)
	@DisplayName("여러 명이 동시에 N번 부르면 정확히 N이 늘어난다")
	void concurrent() throws Exception {
		long id = ready();
		long[] players = { f.leader(), f.choreographer(), f.dancer() };
		int[] next = { 0 };

		List<Integer> statuses = Concurrently.statuses(Concurrently.run(12, () -> {
			long player;
			synchronized (next) {
				player = players[next[0]++ % players.length];
			}
			return api.play(player, id);
		}));

		assertThat(statuses).hasSize(12).containsOnly(204);
		assertThat(jdbc.queryForObject("SELECT play_count FROM practice_video WHERE id = ?", Long.class, id))
			.isEqualTo(12);
	}

	@Test
	@DisplayName("업로드 중이면 올린 디렉터는 409 PRACTICE_VIDEO_NOT_READY, 명단에 없으면 404 PRACTICE_VIDEO_NOT_FOUND이고 세지 않는다")
	void rejected() throws Exception {
		long uploading = api.startUploadId(f.choreographer(), f.performanceId(), f.choreoSong(), "올리는 중");
		long id = ready();

		api.play(f.choreographer(), uploading)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_READY"));
		api.play(f.bystander(), id)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRACTICE_VIDEO_NOT_FOUND"));

		api.detail(f.dancer(), id).andExpect(jsonPath("$.playCount").value(0));
		assertThat(jdbc.queryForObject("SELECT play_count FROM practice_video WHERE id = ?", Long.class, uploading))
			.isZero();
	}

}
